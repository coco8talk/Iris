"""Investigate node implementation."""

from __future__ import annotations

from typing import Literal

import structlog
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage
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
# create_agent 接管了 LLM 调用，M6 的 ainvoke_with_retry 重试层暂时接不进来
# （它是包在裸 bound_model.ainvoke 外面的，create_agent 内部不暴露这个注入点）——
# 已知限制，留给以后给 model 本身包一层带重试的 Runnable 再补。
from agent.paths import incident_dir
from agent.state import IncidentState
from agent.tools.GatewayClient import (
    BudgetExceededError,
    GatewayClient,
    GatewayRequestError,
)
from agent.tools.register import make_template_tools

logger = structlog.getLogger()


def make_investigate_node(model: BaseChatModel):
    """用注入的 model 造出 investigate 节点闭包.

    model 是 get_model(AgentRole.INVESTIGATE) 的原始返回值（还没 bind_tools）——
    query_metrics 工具依赖每个事故自己的 GatewayClient，没法在构建期一次性绑定，
    所以 bind_tools() 仍留在 _investigate 内部按次执行，只有 model 本身的构造挪到了外面。
    """

    async def _investigate(
        state: IncidentState,
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

        user_prompt = (
            f"当前事故 incident_id={incident_id}，需要排查的服务是 service={service}。"
            "请排查这次告警的根因，给出带证据引用的结论。"
        )

        gateway_client = GatewayClient(
            incident_id=incident_id, agent_role=AgentRole.INVESTIGATE
        )
        ledger = load_ledger(state)
        config: RunnableConfig = {"configurable": {"thread_id": incident_id}}

        try:
            check_budget(ledger)
            tools = make_template_tools(gateway_client, ledger)
            evidence_store = EvidenceStore(incident_id)

            if get_settings().subagents_enabled:
                raise NotImplementedError("multi-agent investigator 留给 M10 实现")

            agent = build_simple_investigator(
                model=model,
                tools=tools,
                evidence_store=evidence_store,
                verifier_feedback=state.get("verifier_feedback"),
            )
            llm_response = await agent.ainvoke(
                {"messages": [HumanMessage(user_prompt)]}, config=config
            )

            for message in llm_response["messages"]:
                if isinstance(message, AIMessage):
                    ledger = charge_tokens(ledger, message)

            rca_report = llm_response["structured_response"]
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
                "evidence": rca_report.evidence_ids,
                "investigate_rounds": state.get("investigate_rounds", 0) + 1,
                "budget": ledger.model_dump(),
            },
        )

    return _investigate
