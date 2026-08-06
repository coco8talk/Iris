"""LangGraph single-node graph template.

Returns a predefined response. Replace logic and configuration as needed.
"""

from __future__ import annotations

from typing import Any, Literal

import structlog
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langgraph.constants import END, START
from langgraph.graph import StateGraph
from langgraph.types import Command

from agent.config import AgentRole, get_settings
from agent.guard import (
    BudgetExhaustedError,
    charge_tokens,
    charge_tool_call,
    check_budget,
    load_ledger,
)
from agent.router import get_model
from agent.state import IncidentState
from agent.tools.definetions import make_query_metrics
from agent.tools.GatewayClient import (
    ApiEnvelope,
    BudgetExceededError,
    GatewayClient,
    GatewayRequestError,
)

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


async def investigate_once(
    state: IncidentState,
) -> Command[Literal["verify_once", "report_once"]]:
    """Query one model-selected metric for the affected service.

    出边全部由 Command(goto=...) 决定，图上不能再挂 investigate_once → verify_once 的静态边：
    静态边不会被 Command 抑制，两者并存会让预算耗尽时同时扇出到 report_once 和 verify_once，
    "紧急收口"就永远收不了口。
    """
    service=state.get("service")
    if not service:
        # 没有 service 就永远查不出东西，回流再多轮也一样，直接收口
        return Command(
            goto="report_once",
            update={"degraded": True, "degraded_reason": "no_service"},
        )

    user_prompt = _build_investigate_prompt(state)

    gateway_client = GatewayClient(
        incident_id=state.get("incident_id"),
        agent_role=AgentRole.INVESTIGATE,
    )
    query_metrics = make_query_metrics(gateway_client)

    # 一次 investigate_once 只还原一次台账，之后全程在同一个 ledger 上充值/检查，
    # 中途重新 load 会把本轮已充的 token/调用次数丢掉。
    ledger = load_ledger(state)

    try:
        # bind_tools 只是本地绑定工具描述，不产生调用；检查点落在真正发起调用之前。
        check_budget(ledger)
        investigate_model = get_model(AgentRole.INVESTIGATE).bind_tools([query_metrics])
        ai_response = await investigate_model.ainvoke(
            [SystemMessage(INVESTIGATE_SYSTEM_PROMPT), HumanMessage(user_prompt)]
        )
        ledger = charge_tokens(ledger, ai_response)

        if not isinstance(ai_response, AIMessage) or not ai_response.tool_calls:
            # 这一轮 token 已经花掉了，算一轮已用轮次，否则 route_after_verify
            # 永远看到 rounds=0，会在 verify ↔ investigate 之间无限回流。
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
        envelope = ApiEnvelope.model_validate(await query_metrics.ainvoke(tool_call["args"]))
        ledger = charge_tool_call(ledger, envelope)
    except (GatewayRequestError, BudgetExceededError, BudgetExhaustedError) as e:
        # BudgetExhaustedError 来自本地 guard（预算/墙钟耗尽），
        # BudgetExceededError 来自网关 429，两者都走同一条优雅收口路径。
        exhausted = ledger.model_copy(update={"budget_exhausted": True})
        return Command(
            goto="report_once",
            update={
                "budget": exhausted.model_dump(),
                "degraded": True,
                "degraded_reason": str(e) or type(e).__name__,
            },
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


async def verify_once(state: IncidentState) -> dict[str, Any]:
    """M9 的确定性预检那一半：不调 LLM，纯规则判断证据够不够."""
    if not state.get("metrics_result") or state.get("degraded"):
        return {
                "verify_verdict": {"verdict": "fail", "objections": ["未取到有效指标证据"]},
                "verifier_feedback": ["未取到有效指标证据"]
        }
    return {"verify_verdict": {"verdict": "pass"}}

async def report_once(state: IncidentState) -> None:
    """Generate a report based on the state."""
    logger = structlog.getLogger()
    logger.info(
        "report_once",
        investigate_rounds=state.get("investigate_rounds"),
        verify_verdict=state.get("verify_verdict"),
        budget_exhausted=(state.get("budget") or {}).get("budget_exhausted"),
    )

async def route_after_verify(state: IncidentState) -> Literal["report_once", "investigate_once"]:
    """Verify 之后决定回流还是收口；只读不写 state（rounds 的 +1 在 investigate_once 里）."""
    verdict = (state.get("verify_verdict") or {}).get("verdict")
    max_investigate_rounds = get_settings().max_investigate_rounds

    if verdict == "pass":
        return "report_once"
    # 预算/墙钟已经触顶，再回流也只会立刻被 check_budget 拦下，直接出报告
    elif (state.get("budget") or {}).get("budget_exhausted"):
        return "report_once"
    elif verdict == "fail" and state.get("investigate_rounds", 0) < max_investigate_rounds:
        return "investigate_once"
    else:
        return "report_once"


workflow = StateGraph(IncidentState)
workflow.add_node("investigate_once", investigate_once)
workflow.add_node("verify_once", verify_once)
workflow.add_node("report_once", report_once)

workflow.add_edge(START, "investigate_once")
# investigate_once 的出边由它自己的 Command(goto=...) 给出，这里不挂静态边——
# 静态边和 Command 并存会同时扇出到两个目标，预算耗尽时收不了口。
workflow.add_conditional_edges("verify_once", route_after_verify)
workflow.add_edge("report_once", END)

graph = workflow.compile()
