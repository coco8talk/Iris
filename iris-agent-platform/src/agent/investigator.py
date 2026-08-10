from typing import Literal

from langchain.agents import create_agent
from langchain_core.language_models import BaseChatModel
from langchain_core.tools import BaseTool, tool
from pydantic import BaseModel, Field

from agent.evidence import EvidenceStore
from agent.state import RcaReport

INVESTIGATOR_SYSTEM_PROMPT = """
你是事故排障系统中的 investigate 角色,负责对一次告警做根因排查(RCA)。

你持有以下只读取证工具:
- query_cmdb(service):查服务在 CMDB 里登记的拓扑与实例信息。
- query_metrics(template_key, service, window, compare_baseline):查时序指标,
  template_key 是 error_rate/qps/p99/cpu/memory 之一,一次只能查一个维度。
- query_logs(service, window, end_offset_seconds, pattern):查日志,返回按 pattern
  聚合的日志组,以及从匹配行里提取出的 distinctTraceIds。
- query_trace(trace_id, max_depth):查一条 trace 的完整调用链。trace_id 必须来自
  query_logs 返回的 distinctTraceIds,禁止凭空构造或猜测。
- record_evidence(kind, source, summary, excerpt):登记一条证据,kind 取值
  M(metrics)/L(logs)/T(trace)/S(cmdb 等其他)。调用后返回分配到的 EV-* 编号,
  之后的结论必须引用这个编号。

## 取证顺序
1. 先调 query_cmdb 确认服务确实存在、拿到拓扑信息——service 名对不上,后面所有
   查询都会是空数据。
2. 之后是 metrics/logs/trace:先用较大窗口看整体趋势,再缩小窗口 / 加 pattern
   定位到具体时间段和具体请求。
3. 需要看某条请求的完整调用链时,trace_id 只能从 query_logs 返回的
   distinctTraceIds 里取,不要自己编造或猜测格式正确的 trace_id。

## 证据登记(强制)
- 每次工具调用拿到有意义的结果后,必须调用 record_evidence 登记,不能只在
  回复里描述而不登记。
- summary 是给人看的结论式摘要(≤3 句),excerpt 是能支撑 summary 的最小数据
  片段,不要把整个响应贴进去。
- 工具返回信封里 degraded=true 或 meta.truncated=true 时,登记证据时必须原样
  带出这两个字段,不允许因为数据"看起来差不多够用"就当作完整数据处理。

## 输出结论
- 最终以 RcaReport 结构化对象输出。
- causal_chain 和 root_detail 里的每一条具体断言,都必须能追溯到某个已登记的
  EV-* 编号;evidence_ids 字段列出全部被引用的编号。
- 没有证据支撑、只是你推测的部分,要显式标注为"未验证假设",不能和有证据支撑
  的结论混在一起、不加区分地陈述。
- 如果排查过程中出现过 degraded 或 truncated 的证据,结论里要明确提示"哪部分
  数据不完整/不可用",不能假装排查是在完整数据上做的。
"""

# 回流轮才拼上：把 verify 打回的异议 + 已登记的证据编号一起亮出来，
# 让模型做增量补证而不是把已有取证再走一遍。
INVESTIGATOR_FEEDBACK_PROMPT = """

## 上一轮已被 verify 打回,本轮请做增量补证
异议如下:
{objections}

已登记的证据编号(结论可以继续引用这些,不必为同一份数据重新取证):
{evidence_ids}

规则:
- 只针对上面异议指出的缺口调用新的取证工具,已经查过的维度/窗口不要重复查。
- 已有结论直接复用上面列出的 EV-* 编号引用;只有这一轮新取到的数据才调用
  record_evidence 申请新编号,不要为已经登记过的证据再登记一次。
"""


def format_feedback_section(verifier_feedback: list[str], evidence_ids: list[str]) -> str:
    """把 verify 的驳回意见 + 已登记证据编号拼成一段,追加到 system prompt 后面.

    对应 T7.3 回流补证的三件事(计划原文):亮出已有证据编号、要求增量补证、
    复用已有编号而不是重新取证。
    """
    return INVESTIGATOR_FEEDBACK_PROMPT.format(
        objections="\n".join(f"- {item}" for item in verifier_feedback),
        evidence_ids="\n".join(f"- {eid}" for eid in evidence_ids) or "- (无)",
    )


class RecordEvidenceInput(BaseModel):
    """Input schema for registering one piece of evidence into the incident's evidence ledger."""

    kind: Literal["M", "L", "T", "S"] = Field(
        description=(
            "证据类型：M=metrics(query_metrics)/L=logs(query_logs)/"
            "T=trace(query_trace)/S=其他(query_cmdb 等)。"
        )
    )
    source: str = Field(
        description=(
            "取证来源，写成可复现的调用描述，如 "
            "'query_metrics(template=error_rate,service=pm-question,window=1800)'。"
        )
    )
    degraded: bool = Field(
        description="本次取证对应工具返回信封里的 degraded 字段，原样带入，不许省或改写成 false。"
    )
    truncated: bool = Field(
        description="本次取证对应工具返回信封里 meta.truncated 字段，原样带入，不许省或改写成 false。"
    )
    summary: str = Field(
        description="≤3 句的结论式摘要，例如 'pm-question 的 p99 在 10:12 从 80ms 涨到 2.1s'。"
    )
    excerpt: str = Field(
        description="支撑 summary 的最小数据摘录，不要把整个工具响应贴进来。"
    )


def make_record_evidence_tool(
    evidence_store: EvidenceStore, agent_role: str = "investigate"
) -> BaseTool:
    """Create the record_evidence tool bound to this incident's EvidenceStore.

    evidence_store 每个事故各有一份实例（落盘目录不同），所以这个工具不能像
    definetions.py 里那 5 个网关工具一样是纯函数式的模块级工厂——它必须绑定到
    调用方（investigate 节点）为本次事故构造出的那一个 EvidenceStore。
    """

    @tool("record_evidence", args_schema=RecordEvidenceInput)
    def record_evidence(
        kind: Literal["M", "L", "T", "S"],
        source: str,
        degraded: bool,
        truncated: bool,
        summary: str,
        excerpt: str,
    ) -> str:
        """Register one piece of evidence gathered from a diagnostic tool call.

        Call this immediately after any diagnostic tool call (query_cmdb /
        query_metrics / query_logs / query_trace) returns a meaningful result —
        do not just describe findings in free text without registering them.
        Returns the assigned evidence id (e.g. "EV-M-001"); cite it in your
        final conclusion instead of restating the raw data.
        """
        path = evidence_store.write(
            kind=kind,
            source=source,
            agent_role=agent_role,
            degraded=degraded,
            truncated=truncated,
            summary=summary,
            excerpt=excerpt,
        )
        return path.stem

    return record_evidence


def build_simple_investigator(model: BaseChatModel, tools: list[BaseTool], evidence_store: EvidenceStore, verifier_feedback: list[str]):
    """组装消融 A2 的单 Agent：create_agent(model, tools + [record_evidence], response_format=RcaReport)."""
    record_evidence_tool = make_record_evidence_tool(evidence_store)
    system_prompt = INVESTIGATOR_SYSTEM_PROMPT
    if verifier_feedback:
        system_prompt += format_feedback_section(verifier_feedback, evidence_store.list_ids())
    return create_agent(
        model = model,
        tools = [*tools, record_evidence_tool],
        system_prompt = system_prompt,
        response_format = RcaReport,
    )

