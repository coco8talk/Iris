from typing import Any

import structlog
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.runnables import Runnable
from pydantic import ValidationError

from agent.config import AgentRole
from agent.llm_retry import ainvoke_with_retry
from agent.paths import read_alert_fixture
from agent.state import Domain, IncidentState, Severity, TriageResult

logger = structlog.getLogger()

TRIAGE_FALLBACK = TriageResult(
    severity=Severity.P2,
    suspected_domains=list(Domain),
)

RETRY_HINT = "\n\n上次输出不合法（字段缺失或不在枚举范围内），请严格只输出 schema 要求的字段。"

# 单一数据源：7 个 Domain 的含义只在这里写一遍，prompt 从这里拼，
# 别在 prompt 字符串里手抄一份——两份文本会慢慢漂移不一致。
_DOMAIN_GLOSSARY: dict[Domain, str] = {
    Domain.DB: "数据库层：慢查询、锁等待、连接池打满",
    Domain.THREADPOOL: "线程池/下游超时：池耗尽、队列堆积",
    Domain.JVM: "JVM 层：GC 压力、堆内存、类加载",
    Domain.RESOURCE: "宿主资源：CPU/磁盘/网络饱和",
    Domain.CONTAINER: "容器/实例：宕掉、CrashLoop、副本数不足",
    Domain.CONFIG: "配置变更：Nacos 发布、开关误改",
    Domain.CACHE: "缓存：Redis 不可用、击穿/雪崩",
}

TRIAGE_SYSTEM_PROMPT = """你是事故处理多智能体系统中的 triage 角色。
你会看到一组告警（可能来自同一个事故的多条规则），任务是判断：
1. severity：这组告警对应的事故严重度
2. suspected_domains：疑似的根因技术域，可多选

suspected_domains 只能从下面这 7 个字面值里选，逐字匹配，不要自造新值：
{domain_glossary}

判不准时宁可多选几个域，也不要漏选——漏选比多选损失更大。
""".format(domain_glossary="\n".join(f"- {d.value}：{desc}" for d, desc in _DOMAIN_GLOSSARY.items()))


def _summarize_alerts(alerts: list[dict]) -> str:
    """把 read_alert_fixture() 返回的告警列表压成给模型看的摘要文本."""
    lines = []
    for i, alert in enumerate(alerts, start=1):
        lines.append(
            f"{i}. alertname={alert['alertname']} service={alert['service']}\n"
            f"   labels={alert['labels']}\n"
            f"   annotations={alert['annotations']}"
        )
    return "\n".join(lines)


def _build_triage_user_prompt(alerts: list[dict]) -> str:
    return (
        f"以下是本次事故收到的 {len(alerts)} 条告警，请据此判断 severity 和 suspected_domains：\n\n"
        f"{_summarize_alerts(alerts)}"
    )


def make_triage_node(model: Runnable):
    """用注入的 model 造出 triage 节点闭包.

    model 由调用方在 build_graph() 里构造好传入（get_model(AgentRole.TRIAGE)
    .with_structured_output(TriageResult)），节点体内不直接 get_model()——
    M12 的消融开关/临时换模型只需要改装配处传进来的 model，不用碰这里的逻辑。
    """

    async def _ainvoke_triage(
        alert_list: list[dict[str, Any]], incident_id: str | None = None
    ):
        base_prompt = _build_triage_user_prompt(alert_list)
        for attempt in range(2):
            try:
                user_prompt = base_prompt if attempt == 0 else base_prompt + RETRY_HINT
                # 这一层循环重试的是「输出不合 schema」，传输层断连是另一码事，
                # 由 ainvoke_with_retry 单独兜（两者正交，可以嵌套）。
                response = await ainvoke_with_retry(
                    model,
                    [SystemMessage(TRIAGE_SYSTEM_PROMPT), HumanMessage(user_prompt)],
                    incident_id=incident_id,
                    agent_role=AgentRole.TRIAGE.value,
                )
                return response, None
            except ValidationError as e:
                logger.warning("triage_parse_failed", attempt=attempt, error=str(e))
        return TRIAGE_FALLBACK, "triage_fallback"

    async def _triage(state: IncidentState) -> dict:
        alerts_ref = state["alerts_ref"]
        alert_list = read_alert_fixture(alerts_ref)
        incident_id = state.get("incident_id")
        response, error = await _ainvoke_triage(alert_list, incident_id=incident_id)
        logger.info(
            "triage",
            incident_id=incident_id,
            severity=response.severity,
            suspected_domains=response.suspected_domains,
            degraded_reason=error,
        )
        update: dict = {"triage": response}
        if error:
            update["degraded_reason"] = error
        return update

    return _triage
