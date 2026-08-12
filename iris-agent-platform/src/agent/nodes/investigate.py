"""Investigate node implementation."""

from __future__ import annotations

from typing import Literal

import structlog
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from langchain_core.runnables import RunnableConfig
from langgraph.types import Command

from agent.config import AgentRole, get_settings
from agent.evidence import EvidenceStore
from agent.guard import (
    BudgetExhaustedError,
    charge_tokens,
    check_budget,
    load_ledger,
)
from agent.investigator import build_simple_investigator
from agent.lead import run_lead_investigation

# create_agent 接管了 LLM 调用，M6 的 ainvoke_with_retry 重试层暂时接不进来
# （它是包在裸 bound_model.ainvoke 外面的，create_agent 内部不暴露这个注入点）——
# 已知限制，留给以后给 model 本身包一层带重试的 Runnable 再补。
from agent.paths import incident_dir
from agent.state import BudgetLedger, IncidentState, RcaReport
from agent.tools.GatewayClient import (
    BudgetExceededError,
    GatewayClient,
    GatewayRequestError,
)
from agent.tools.register import make_raw_tools, make_template_tools

logger = structlog.getLogger()


# run_inner 的统一返回：结构化 RCA 报告 + 内层产生的全部消息 + hypotheses.md 落盘路径
# （lead 路径才有，M7 单 Agent 路径恒为 None）。
# messages 必须带出来——预算记账（charge_tokens）是两条路径共用的规则，留在节点里
# 统一做；若只返回 RcaReport，token 就漏记了，而漏记不会报错，只会让 M4 的预算
# 算出一个偏小的数字。
InnerResult = tuple[RcaReport, list[BaseMessage], str | None]


def _user_prompt(state: IncidentState) -> str:
    """两条路径共用的事故描述 prompt——切不切多 Agent 不该顺带改变任务描述，否则 A2 消融不可比."""
    return (
        f"当前事故 incident_id={state['incident_id']}，"
        f"需要排查的服务是 service={state['service']}。"
        "请排查这次告警的根因，给出带证据引用的结论。"
    )


async def _run_simple(
    state: IncidentState,
    *,
    model: BaseChatModel,
    client: GatewayClient,
    ledger: BudgetLedger,
    store: EvidenceStore,
    config: RunnableConfig,
) -> InnerResult:
    """M7 的单 Agent 实现（消融 A2 的对照组）：全量工具塞给一个 create_agent.

    config 形参只是为了和另外两个引擎签名一致，供 run_inner 统一分发——这里维持
    M7 原有行为，不用外面传进来的 config（该行为是 M7 已验收的既有实现，不在
    T10.1 改动范围内，仅记一笔：它自建的 thread_id 和外层 IncidentState 图的
    thread_id 相同，但没有真正的 checkpointer 挂载，本来就不提供中间态恢复）。
    """
    incident_id = state["incident_id"]
    tools = make_template_tools(client, ledger) + make_raw_tools(client, ledger)
    agent = build_simple_investigator(
        model=model,
        tools=tools,
        evidence_store=store,
        verifier_feedback=state.get("verifier_feedback", []),
    )
    simple_config: RunnableConfig = {"configurable": {"thread_id": incident_id}}
    llm_response = await agent.ainvoke(
        {"messages": [HumanMessage(_user_prompt(state))]}, config=simple_config
    )
    return llm_response["structured_response"], llm_response["messages"], None


async def _run_lead(
    state: IncidentState,
    *,
    model: BaseChatModel,
    client: GatewayClient,
    ledger: BudgetLedger,
    store: EvidenceStore,
    config: RunnableConfig,
) -> InnerResult:
    """T10.2：lead + 三 subagent 接管 investigate（subagents_enabled=True 时的路径）.

    client 形参不用于 lead 自己的取证——run_lead_investigation 内部会为 lead 和
    三个 subagent 各建一份带各自 agent_role 的 GatewayClient，否则网关审计里全会
    记成 AgentRole.INVESTIGATE，分不出层。这里保留形参只是为了和 _run_simple
    共用 run_inner 的统一分发签名。
    """
    return await run_lead_investigation(
        state, model=model, ledger=ledger, store=store, config=config
    )


async def run_inner(
    state: IncidentState,
    *,
    model: BaseChatModel,
    client: GatewayClient,
    ledger: BudgetLedger,
    store: EvidenceStore,
    config: RunnableConfig,
) -> InnerResult:
    """按 settings.subagents_enabled 分发到 lead（多 Agent）或 M7 单 Agent（A2 消融）.

    T10.1 的 inner_engine 三选一开关只在 spike 期间有意义；选型结束（deepagents
    胜出）后多 Agent 路径只剩一种实现，运行时只需要一个二元开关，T10.2 起统一用
    subagents_enabled 表达。节点只负责预算 guard、异常降级、落盘与 Command 返回；
    内层跑哪条路径对它透明。
    """
    subagents_enabled = get_settings().subagents_enabled
    logger.info(
        "inner_engine_dispatch",
        incident_id=state["incident_id"],
        subagents_enabled=subagents_enabled,
    )
    run = _run_lead if subagents_enabled else _run_simple
    return await run(
        state, model=model, client=client, ledger=ledger, store=store, config=config
    )


def make_investigate_node(model: BaseChatModel):
    """用注入的 model 造出 investigate 节点闭包.

    model 是 get_model(AgentRole.INVESTIGATE) 的原始返回值（还没 bind_tools）——
    query_metrics 工具依赖每个事故自己的 GatewayClient，没法在构建期一次性绑定，
    所以 bind_tools() 仍留在 _investigate 内部按次执行，只有 model 本身的构造挪到了外面。
    """

    async def _investigate(
        state: IncidentState,
            config: RunnableConfig,
    ) -> Command[Literal["verify_once", "report_once"]]:
        service = state.get("service")
        incident_id = state.get("incident_id")
        if not service or not incident_id:
            missing_field = "service" if not service else "incident_id"
            logger.warning(
                "investigate_skip",
                incident_id=incident_id,
                reason=f"no_{missing_field}",
            )
            return Command(
                goto="report_once",
                update={"degraded": True, "degraded_reason": f"no_{missing_field}"},
            )

        gateway_client = GatewayClient(
            incident_id=incident_id, agent_role=AgentRole.INVESTIGATE
        )
        ledger = load_ledger(state)

        try:
            check_budget(ledger)
            evidence_store = EvidenceStore(incident_id)

            rca_report, messages, hypotheses_ref = await run_inner(
                state,
                model=model,
                client=gateway_client,
                ledger=ledger,
                store=evidence_store,
                config=config
            )

            for message in messages:
                if isinstance(message, AIMessage):
                    ledger = charge_tokens(ledger, message)
        except (GatewayRequestError, BudgetExceededError, BudgetExhaustedError) as e:
            logger.warning(
                "investigate_exhausted",
                incident_id=incident_id,
                reason=str(e) or type(e).__name__,
            )
            exhausted = ledger.model_copy(update={"budget_exhausted": True})
            return Command(
                goto="report_once",
                update={
                    "budget": exhausted.model_dump(),
                    "degraded": True,
                    "degraded_reason": str(e) or type(e).__name__,
                    "investigate_rounds": state.get("investigate_rounds", 0) + 1,
                },
            )

        report_path = incident_dir(incident_id) / "report_draft.md"
        report_path.write_text(rca_report.model_dump_json(indent=2), encoding="utf-8")

        logger.info(
            "investigate_done",
            incident_id=incident_id,
            root_service=rca_report.root_service,
            evidence_ids=rca_report.evidence_ids,
            investigate_rounds=state.get("investigate_rounds", 0) + 1,
        )
        return Command(
            goto="verify_once",
            update={
                "draft_report_ref": str(report_path),
                "hypotheses_ref": hypotheses_ref,
                "evidence": rca_report.evidence_ids,
                "investigate_rounds": state.get("investigate_rounds", 0) + 1,
                "budget": ledger.model_dump(),
            },
        )

    return _investigate
