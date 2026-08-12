"""M10 T10.2 三个最小权限 subagent 的定义.

deepagents 版（create_deep_agent 的 subagents= 规格：name/description/system_prompt/tools
四个字段的 dict）。每个 builder 都自己新建一个 GatewayClient，而不是接收外面传进来的
client——X-Agent-Role 不同，网关审计的 agent_role 列才分得出层；共用 lead 自己的
client 会导致三个 subagent 的调用全部记成 lead 一个角色（T10.2 完成判定第一条）。
"""

from agent.config import AgentRole
from agent.evidence import EvidenceStore
from agent.investigator import make_record_evidence_tool
from agent.state import BudgetLedger
from agent.tools.GatewayClient import GatewayClient
from agent.tools.register import make_raw_tools, make_template_tools

_SERVICE_SCOPE_RULE = """
- service 参数只能用 lead 在任务描述里明确给出的服务名，不能自己发挥。日志/指标内容里
  经常会提到 redis、mysql、kafka 这类下游依赖组件的名字（比如"Redis 连接池初始化"），
  那些是被排查服务用到的组件，不是 CMDB 里注册的服务名——拿它们当 service 参数去查
  会被网关 400 拒绝，还会白白消耗模板预算（全事故只有 30 次）。如果怀疑根因在某个
  依赖组件上，把这个怀疑写进结束总结交给 lead，不要自己拿依赖组件名发起新查询。
"""

METRICS_INVESTIGATOR_PROMPT = f"""
你是事故排查系统里的 metrics 专项 subagent，只负责查时序指标、判断是否有异常、登记证据。
- 用 query_metrics 查看关键维度（error_rate/p99_latency/cpu_usage 等），一次一个维度。
{_SERVICE_SCOPE_RULE}
- 每次拿到有意义的结果后调用 record_evidence 登记，summary 写成结论式的一两句话。
- 结束时用一段话（≤3 句）总结你查到了什么、登记了哪些 EV 编号——这段话会原样交给 lead，
  不要在里面堆原始数据，lead 看不到你这里的中间过程。
"""

LOGS_INVESTIGATOR_PROMPT = f"""
你是事故排查系统里的 logs 专项 subagent，只负责查日志、判断异常模式、登记证据。
- 优先用 query_logs（模板通道）按 pattern 聚合查看；模板确实表达不了目标查询时才用
  query_logs_raw（逃生通道，全事故只有 5 次额度，用之前想清楚值不值，不要试探性调用）。
{_SERVICE_SCOPE_RULE}
- query_logs 返回里带 distinctTraceIds——如果发现了可疑请求，把其中的 trace_id 原样
  写进结束总结里交给 lead 转给 trace-investigator，不要自己去查 trace（你没有 query_trace 工具）。
- 每次拿到有意义的结果后调用 record_evidence 登记。
- 结束时用一段话（≤3 句）总结你查到了什么、登记了哪些 EV 编号、有没有可疑 trace_id 要移交。
"""

TRACE_INVESTIGATOR_PROMPT = """
你是事故排查系统里的 trace 专项 subagent，只负责查调用链、定位耗时或出错的具体环节、登记证据。
- trace_id 必须从 lead 转交给你的任务描述里取（通常来自 logs-investigator 的移交），
  禁止自己编造或猜测格式正确的 trace_id。
- 如果任务描述里没有给出任何 trace_id，直接在结束总结里说明"未收到可查的 trace_id"，
  这是正常结果，不要瞎猜着查。
- 每次拿到有意义的结果后调用 record_evidence 登记。
- 结束时用一段话（≤3 句）总结你查到了什么、登记了哪些 EV 编号。
"""


def _subagent_client(incident_id: str, role: AgentRole) -> GatewayClient:
    return GatewayClient(incident_id=incident_id, agent_role=role)


def build_metrics_subagent(incident_id: str, ledger: BudgetLedger, store: EvidenceStore) -> dict:
    """只持 query_metrics + record_evidence——metrics_raw 不给（表里 lead 也没有，等于不开放）."""
    client = _subagent_client(incident_id, AgentRole.METRICS_INVESTIGATOR)
    template_tools = make_template_tools(client, ledger)
    metrics_tool = next(t for t in template_tools if t.name == "query_metrics")
    record_evidence_tool = make_record_evidence_tool(
        store, agent_role=AgentRole.METRICS_INVESTIGATOR.value
    )
    return {
        "name": "metrics-investigator",
        "description": "查时序指标（error_rate/p99_latency/cpu_usage 等）、判断是否异常、登记证据。",
        "system_prompt": METRICS_INVESTIGATOR_PROMPT,
        "tools": [metrics_tool, record_evidence_tool],
    }


def build_logs_subagent(incident_id: str, ledger: BudgetLedger, store: EvidenceStore) -> dict:
    """持 query_logs + query_logs_raw + record_evidence，raw 通道只给这一个 subagent.

    metrics_raw 完全不发，logs_raw 只发给这一个角色：全事故 raw 预算只有 5 次，
    三个 subagent 都能碰会被瞬间打光（T7.4 已定的预算，这里不改）。
    """
    client = _subagent_client(incident_id, AgentRole.LOGS_INVESTIGATOR)
    template_tools = make_template_tools(client, ledger)
    logs_tool = next(t for t in template_tools if t.name == "query_logs")
    raw_tools = make_raw_tools(client, ledger)
    logs_raw_tool = next(t for t in raw_tools if t.name == "query_logs_raw")
    record_evidence_tool = make_record_evidence_tool(
        store, agent_role=AgentRole.LOGS_INVESTIGATOR.value
    )
    return {
        "name": "logs-investigator",
        "description": (
            "查日志、判断异常模式、必要时用 raw 逃生通道、登记证据；"
            "发现可疑 trace_id 要在总结里移交给 trace-investigator。"
        ),
        "system_prompt": LOGS_INVESTIGATOR_PROMPT,
        "tools": [logs_tool, logs_raw_tool, record_evidence_tool],
    }


def build_trace_subagent(incident_id: str, ledger: BudgetLedger, store: EvidenceStore) -> dict:
    """只持 query_trace + record_evidence."""
    client = _subagent_client(incident_id, AgentRole.TRACE_INVESTIGATOR)
    template_tools = make_template_tools(client, ledger)
    trace_tool = next(t for t in template_tools if t.name == "query_trace")
    record_evidence_tool = make_record_evidence_tool(
        store, agent_role=AgentRole.TRACE_INVESTIGATOR.value
    )
    return {
        "name": "trace-investigator",
        "description": (
            "查一条调用链、定位耗时或出错的具体环节、登记证据；"
            "trace_id 必须来自 lead 转交，不接受自己猜。"
        ),
        "system_prompt": TRACE_INVESTIGATOR_PROMPT,
        "tools": [trace_tool, record_evidence_tool],
    }
