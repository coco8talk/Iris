"""Investigate node implementation."""

from __future__ import annotations

from typing import Literal

import structlog
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.runnables import Runnable, RunnableConfig
from langgraph.types import Command

from agent.config import AgentRole
from agent.guard import (
    BudgetExhaustedError,
    charge_tokens,
    charge_tool_call,
    check_budget,
    load_ledger,
)
from agent.llm_retry import ainvoke_with_retry
from agent.state import IncidentState
from agent.tools.definetions import make_query_metrics
from agent.tools.GatewayClient import (
    ApiEnvelope,
    BudgetExceededError,
    GatewayClient,
    GatewayRequestError,
)

logger = structlog.getLogger()

INVESTIGATE_SYSTEM_PROMPT = """
你是事故排障多智能体系统中的 investigate 角色。
当前你只有一个工具可用：query_metrics，用于查询指定服务的时序监控指标（经网关代理 Prometheus）。

任务：根据用户消息中给出的 service，选择一个合适的指标维度
（error_rate / qps / p99 / cpu / memory）和查询窗口，调用 query_metrics 获取数据。
本轮你必须调用该工具——不要只用文字回答，不要臆造数据。

规则：
- 每次只能查一个维度；如果不确定选哪个，默认用 error_rate。
- 先用较大窗口（如 30m）做整体判断，不要一上来就切太窄的窗口。
- 工具可能返回 degraded=true（网关降级），这不是你的错，如实反映，不要隐瞒。
"""

INVESTIGATE_USER_PROMPT = (
    "当前事故 incident_id={incident_id}，需要排查的服务是 service={service}。"
    "请调用 query_metrics 工具，初步了解该服务的健康状况。"
)

# 回流第 2 轮才拼上：把上一轮 verify 打回的异议注入，避免模型原样重跑同一次查询
INVESTIGATE_FEEDBACK_PROMPT = """

上一轮排查已被 verify 打回，异议如下：
{objections}

本轮请做增量补证：复用已经拿到的 evidence，不要重复取同一份数据
（同一个 template_key + 同样的 window 就是同一份数据）。
针对上面的异议换一个还没查过的指标维度，或换一个时间窗口，只补缺的那部分证据。
已查过的证据如下（args 相同即视为重复）：
{evidence}
"""


def _build_investigate_prompt(state: IncidentState) -> str:
    """拼 investigate 的用户消息；有 verifier_feedback（即回流轮）时追加增量补证段."""
    user_prompt = INVESTIGATE_USER_PROMPT.format(
        incident_id=state.get("incident_id"),
        service=state.get("service"),
    )

    feedback = state.get("verifier_feedback") or []
    if not feedback:
        return user_prompt

    evidence = state.get("evidence") or []
    return user_prompt + INVESTIGATE_FEEDBACK_PROMPT.format(
        objections="\n".join(f"- {item}" for item in feedback),
        evidence="\n".join(f"- {item}" for item in evidence) or "- （无）",
    )


def make_investigate_node(model: Runnable):
    """用注入的 model 造出 investigate 节点闭包.

    model 是 get_model(AgentRole.INVESTIGATE) 的原始返回值（还没 bind_tools）——
    query_metrics 工具依赖每个事故自己的 GatewayClient，没法在构建期一次性绑定，
    所以 bind_tools() 仍留在 _investigate 内部按次执行，只有 model 本身的构造挪到了外面。
    """

    async def _investigate(
        state: IncidentState,
    ) -> Command[Literal["verify_once", "report_once"]]:
        """Query one model-selected metric for the affected service.

        出边全部由 Command(goto=...) 决定，图上不能再挂 investigate_once → verify_once 的静态边：
        静态边不会被 Command 抑制，两者并存会让预算耗尽时同时扇出到 report_once 和 verify_once，
        "紧急收口"就永远收不了口。
        """
        service = state.get("service")
        incident_id = state.get("incident_id")
        if not service or not incident_id:
            # 没有 service 或 incident_id 时无法查询网关，直接降级收口。
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

        user_prompt = _build_investigate_prompt(state)

        gateway_client = GatewayClient(
            incident_id=incident_id,
            agent_role=AgentRole.INVESTIGATE,
        )
        query_metrics = make_query_metrics(gateway_client)

        # 一次 investigate_once 只还原一次台账，之后全程在同一个 ledger 上充值/检查，
        # 中途重新 load 会把本轮已充的 token/调用次数丢掉。
        ledger = load_ledger(state)
        config: RunnableConfig = {"configurable": {"thread_id": incident_id}}

        try:
            # bind_tools 只是本地绑定工具描述，不产生调用；检查点落在真正发起调用之前。
            check_budget(ledger)
            bound_model = model.bind_tools([query_metrics])
            ai_response = await ainvoke_with_retry(
                bound_model,
                [SystemMessage(INVESTIGATE_SYSTEM_PROMPT), HumanMessage(user_prompt)],
                config=config,
                incident_id=incident_id,
                agent_role=AgentRole.INVESTIGATE.value,
            )
            ledger = charge_tokens(ledger, ai_response)

            if not isinstance(ai_response, AIMessage) or not ai_response.tool_calls:
                # 这一轮 token 已经花掉了，算一轮已用轮次，否则 route_after_verify
                # 永远看到 rounds=0，会在 verify ↔ investigate 之间无限回流。
                logger.warning(
                    "investigate_no_tool_call",
                    incident_id=incident_id,
                    investigate_rounds=state.get("investigate_rounds", 0) + 1,
                )
                return Command(
                    goto="verify_once",
                    update={
                        "degraded": True,
                        "degraded_reason": "investigate_model_no_tool_call",
                        "investigate_rounds": state.get("investigate_rounds", 0) + 1,
                        "budget": ledger.model_dump(),
                    },
                )

            tool_call = ai_response.tool_calls[0]

            check_budget(ledger)
            envelope = ApiEnvelope.model_validate(
                await query_metrics.ainvoke(tool_call["args"], config=config)
            )
            ledger = charge_tool_call(ledger, envelope)
        except (GatewayRequestError, BudgetExceededError, BudgetExhaustedError) as e:
            # BudgetExhaustedError 来自本地 guard（预算/墙钟耗尽），
            # BudgetExceededError 来自网关 429，两者都走同一条优雅收口路径。
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
                },
            )

        logger.info(
            "investigate_done",
            incident_id=incident_id,
            tool_args=tool_call["args"],
            degraded=envelope.degraded,
            investigate_rounds=state.get("investigate_rounds", 0) + 1,
        )
        return Command(
            goto="verify_once",
            update={
                "metrics_result": envelope.data,
                "degraded": envelope.degraded,
                "degraded_reason": envelope.degraded_reason,
                "evidence": [{"tool": "query_metrics", "args": tool_call["args"]}],
                "investigate_rounds": state.get("investigate_rounds", 0) + 1,
                "budget": ledger.model_dump(),
            },
        )

    return _investigate
