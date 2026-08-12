"""M10 T10.2 lead 编排：deepagents 版（T10.1 选型胜出），offload 受限工具做成 R11 硬约束.

四拍节奏（建地基 → 首轮并行广撒 → 汇总假设 → 定向追查）拆成两个 create_deep_agent
串联，不是让一个 agent 自己从头跑到尾决定："response_format=X" 是整个 ReAct 循环
最终要交出的结构，第 3 拍要产出 HypothesesDraft、第 4 拍要产出 RcaReport，两种结构
不一样，一个 agent 实例中途切换不了，所以拆两段是硬约束，不是过度设计。

两段是否能在同一个 config（同一 thread_id）下正确落成连续可恢复的 checkpoint，
没有实测过——按 T10.1 的方法（kill-resume 看网关审计条数）验证一遍，不要假设它对。
"""

from deepagents import create_deep_agent
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import BaseMessage, HumanMessage
from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool
from pydantic import BaseModel, Field

from agent.config import AgentRole
from agent.evidence import EvidenceStore
from agent.paths import incident_dir
from agent.state import BudgetLedger, IncidentState, RcaReport
from agent.subagents import (
    build_logs_subagent,
    build_metrics_subagent,
    build_trace_subagent,
)
from agent.tools.GatewayClient import GatewayClient
from agent.tools.register import make_template_tools


class Hypothesis(BaseModel):
    """一条根因假设：描述 + 支持/反对它的证据编号 + 下一步还缺什么证据."""

    description: str = Field(description="假设描述，一句话说清疑似根因是什么")
    supporting_evidence: list[str] = Field(default_factory=list, description="支持这条假设的 EV-* 编号")
    opposing_evidence: list[str] = Field(default_factory=list, description="反对/削弱这条假设的 EV-* 编号")
    next_evidence_needed: str = Field(description="要证实/证伪这条假设，下一步还需要什么证据")


class HypothesesDraft(BaseModel):
    """recon_agent 第一阶段的结构化产出：全部假设列表."""

    hypotheses: list[Hypothesis]


LEAD_RECON_PROMPT = """
你是事故排查系统的 lead，负责编排三个专项 subagent（metrics-investigator /
logs-investigator / trace-investigator）完成第一阶段排查。你自己不直接查任何
取证工具（query_metrics/query_logs/query_trace 都不在你手上）。

严格按顺序做以下三件事：
1. 建地基：先调用 query_cmdb 确认服务拓扑（下游依赖、部署实例）。**query_cmdb 只
   查排查目标本身这一个服务名**，不要拿日志/假设里提到的下游依赖组件名（比如
   "Redis 连接池初始化"里的 redis）去试探性调用 query_cmdb——那些组件大概率没有
   在 CMDB 里注册成独立服务，一旦 404/400 会直接把整条排查中断掉，而不是像
   subagent 的取证工具那样只影响那一次调用。怀疑根因在某个未验证的依赖上，
   写进最终结论的"未验证假设"里，不要用工具去验证它。
2. 首轮并行广撒：拿到拓扑后，把 metrics-investigator / logs-investigator /
   trace-investigator 三个 subagent 一次性都派出去（同一轮里发起三次派活，
   不要一个一个串行等），各自查本域、回一份摘要给你。**每次派活的任务描述里
   必须明确写出 service=<query_cmdb 确认过的那个服务名>**——metrics-investigator
   和 logs-investigator 没有 query_cmdb，只能照你给的服务名去查，你不写清楚，
   它们就可能把日志里提到的下游依赖组件名（redis/mysql 之类）当成服务名去查，
   那些不是注册服务，会被网关拒绝、白耗预算。trace-investigator 这一轮大概率
   还没有 trace_id 可查（要等 logs-investigator 回报），如果它明确回报
   "未收到可查的 trace_id"，这是正常结果，不算排查失败。
3. 汇总更新假设：基于三份摘要，形成若干条根因假设，每条给出描述、支持它的
   EV 编号、反对它的 EV 编号、下一步还缺什么证据。

你自己没有批量读证据的工具——只有 read_evidence_summary(evidence_id) 能读单条
证据的摘要，需要核实某条 EV 具体写了什么时才用它，不要试图绕过去读全部证据。

最终以结构化结果输出全部假设，不要额外输出别的内容。
"""

LEAD_FOLLOWUP_PROMPT = """
你是事故排查系统的 lead，现在是第二阶段：定向追查。

你会收到本次事故要排查的服务名，以及上一阶段整理出的假设列表（每条带描述/支持
EV/反对 EV/下一步需要的证据）。按"下一步还缺什么证据"挑 1~2 个最需要补证的假设，
只派与之对应的 1~2 个 subagent 再查一轮——不要把三个 subagent 全部再派一遍，
上一阶段已经查过的维度不需要重查。**派活的任务描述里必须写出 service=<开头给你的
那个服务名>**，不要让 subagent 自己从假设描述里猜测服务名，更不能让它们把假设里
提到的下游依赖组件名（redis/mysql 之类）当成 service 参数去查——那些会被网关拒绝。
你自己手上的 query_cmdb 同理：只能再次确认开头给你的那个服务名，不要拿假设里
提到的依赖组件名去试探性调用，未注册的服务名会让 query_cmdb 直接 400，把整条
排查中断掉。

拿到补充摘要后，给出最终结构化 RCA 结论：causal_chain 和 root_detail 里的每一条
具体断言都必须能追溯到某个已登记的 EV-* 编号，evidence_ids 列出全部引用编号。
没有证据支撑、只是推测的部分要显式标注为"未验证假设"。

你自己没有批量读证据的工具——只有 read_evidence_summary(evidence_id) 能读单条
证据的摘要。
"""

PLAN_MD = """# plan

lead 四拍编排：
1. 建地基：query_cmdb 确认服务拓扑
2. 首轮并行广撒：metrics / logs / trace 三个 subagent 同时取证
3. 汇总更新假设：写 hypotheses.md
4. 定向追查：按假设挑 1~2 个 subagent 再查一轮，产出 report_draft.md
"""


def _lead_tools(incident_id: str, ledger: BudgetLedger, store: EvidenceStore) -> list:
    """Lead 自己的工具集：query_cmdb（建地基）+ read_evidence_summary（R11 offload 硬约束）.

    刻意不给 record_evidence——lead 不取证，取证是三个 subagent 的事；也刻意不给
    任何列目录/批量读工具。护栏靠"这个工具不存在"，不靠 prompt 里写"请不要批量读"。

    用 AgentRole.LEAD 建自己的 client，不复用外层节点传入的 client（那个是
    AgentRole.INVESTIGATE，给 M7 单 Agent 路径用的）——否则网关审计里 lead 的
    query_cmdb 调用会被记成 investigate 角色，分不出层。
    """
    lead_client = GatewayClient(incident_id=incident_id, agent_role=AgentRole.LEAD)
    template_tools = make_template_tools(lead_client, ledger)
    query_cmdb_tool = next(t for t in template_tools if t.name == "query_cmdb")

    @tool("read_evidence_summary")
    def read_evidence_summary(evidence_id: str) -> str:
        """读一条已登记证据的 summary 段（不含 excerpt 原文）.

        evidence_id 形如 'EV-M-001'，必须是某个 subagent 已经登记过的编号。
        这是你能读到的最小粒度——没有列目录、没有批量读、没有读全文的工具。
        """
        return store.read_summary(evidence_id)

    return [query_cmdb_tool, read_evidence_summary]


def _build_subagents(incident_id: str, ledger: BudgetLedger, store: EvidenceStore) -> list[dict]:
    return [
        build_metrics_subagent(incident_id, ledger, store),
        build_logs_subagent(incident_id, ledger, store),
        build_trace_subagent(incident_id, ledger, store),
    ]


def _format_hypotheses_md(draft: HypothesesDraft) -> str:
    lines = ["# hypotheses\n"]
    for i, h in enumerate(draft.hypotheses, start=1):
        lines.append(f"## H{i}. {h.description}")
        lines.append(f"- 支持：{', '.join(h.supporting_evidence) or '（无）'}")
        lines.append(f"- 反对：{', '.join(h.opposing_evidence) or '（无）'}")
        lines.append(f"- 下一步需要：{h.next_evidence_needed}\n")
    return "\n".join(lines)


def _format_hypotheses_for_prompt(draft: HypothesesDraft) -> str:
    return "\n".join(
        f"- {h.description}（支持：{h.supporting_evidence or '无'}，"
        f"反对：{h.opposing_evidence or '无'}，下一步：{h.next_evidence_needed}）"
        for h in draft.hypotheses
    )


async def run_lead_investigation(
    state: IncidentState,
    *,
    model: BaseChatModel,
    ledger: BudgetLedger,
    store: EvidenceStore,
    config: RunnableConfig,
) -> tuple[RcaReport, list[BaseMessage], str]:
    """T10.2 lead 编排入口。返回 (RcaReport, 全部消息, hypotheses.md 落盘路径).

    hypotheses.md/plan.md 由这里的 Python 代码确定性写盘，不是 lead 自己调工具写——
    deepagents 默认给的 StateBackend 是虚拟文件系统（存在 LangGraph state 里，不是
    真磁盘），本来就写不到 runs/incidents/ 下；如果改成挂 FilesystemBackend 指向
    incident_dir()，lead 会连 evidence/ 目录一起看见，直接破坏 offload 硬约束，
    所以这里不给 lead 任何文件系统工具，落盘全部由节点代码做，与 report_draft.md
    的既有写法（investigate.py 里直接 write_text）保持一致。
    """
    incident_id = state["incident_id"]
    service = state["service"]

    lead_dir = incident_dir(incident_id)
    (lead_dir / "plan.md").write_text(PLAN_MD, encoding="utf-8")

    tools = _lead_tools(incident_id, ledger, store)
    subagents = _build_subagents(incident_id, ledger, store)

    # checkpointer=True：用调用时 config 里活跃的 checkpointer，按本图自己的节点
    # 粒度落检查点（与 T10.1 _run_deepagents 一致的持久化策略）。
    recon_agent = create_deep_agent(
        model=model,
        system_prompt=LEAD_RECON_PROMPT,
        tools=tools,
        subagents=subagents,
        response_format=HypothesesDraft,
        checkpointer=True,
    )
    recon_result = await recon_agent.ainvoke(
        {
            "messages": [
                HumanMessage(
                    f"当前事故 incident_id={incident_id}，需要排查的服务是 service={service}。"
                    "请先建地基、再首轮并行派三个 subagent 取证，最后给出结构化假设列表。"
                )
            ]
        },
        config=config,
    )
    hypotheses_draft: HypothesesDraft = recon_result["structured_response"]

    hypotheses_path = lead_dir / "hypotheses.md"
    hypotheses_path.write_text(_format_hypotheses_md(hypotheses_draft), encoding="utf-8")

    followup_agent = create_deep_agent(
        model=model,
        system_prompt=LEAD_FOLLOWUP_PROMPT,
        tools=tools,
        subagents=subagents,
        response_format=RcaReport,
        checkpointer=True,
    )
    followup_result = await followup_agent.ainvoke(
        {
            "messages": [
                HumanMessage(
                    f"本次事故排查的服务是 service={service}（派活时把这个服务名原样交给\n"
                    f"subagent，不要让它们自己猜）。\n\n"
                    f"上一阶段整理出的假设列表：\n{_format_hypotheses_for_prompt(hypotheses_draft)}\n\n"
                    "请挑 1~2 个最需要补证的假设定向追查，给出最终结构化 RCA 结论。"
                )
            ]
        },
        config=config,
    )

    all_messages = recon_result["messages"] + followup_result["messages"]
    return followup_result["structured_response"], all_messages, str(hypotheses_path)
