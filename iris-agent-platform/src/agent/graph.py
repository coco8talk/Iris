"""LangGraph single-node graph template.

Returns a predefined response. Replace logic and configuration as needed.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langgraph.constants import END, START
from langgraph.graph import StateGraph

from agent.config import AgentRole
from agent.router import get_model
from agent.tools.definetions import make_query_metrics
from agent.tools.GatewayClient import (
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


@dataclass
class State:
    """Store incident inputs and evidence produced by investigation nodes."""

    # 输入：跨步骤都要用（M4 的 triage/investigate/verify/report 每个节点都读得到），且事后无法重新生成 → 必须存
    incident_id: str
    service: str

    # 工具调用的原始结果：存"数据"不存"格式化好的文本"
    # metrics_result 是 query_metrics 信封 data 字段的原样摘要（dict），不是拼给人看的一段话——
    # 要不要格式化成 prompt 文本，留给后面写 prompt 的节点自己决定
    metrics_result: dict | None = None

    # degraded 是 H3 的一等信号：网关降级时必须原样透传进 state，节点不能吞掉
    degraded: bool = False
    degraded_reason: str | None = None

    # 给 M4 状态机预留：后续每个节点调完工具都要能往这里追加一条证据
    evidence: list[dict] = field(default_factory=list)


async def investigate_once(state: State) -> dict[str, Any]:
    """Query one model-selected metric for the affected service."""
    user_prompt = INVESTIGATE_USER_PROMPT.format(
        incident_id=state.incident_id,
        service=state.service,
    )

    gateway_client = GatewayClient(
        incident_id=state.incident_id,
        agent_role=AgentRole.INVESTIGATE,
    )
    query_metrics = make_query_metrics(gateway_client)

    investigate_model = get_model(AgentRole.INVESTIGATE).bind_tools([query_metrics])
    ai_response = await investigate_model.ainvoke(
        [SystemMessage(INVESTIGATE_SYSTEM_PROMPT), HumanMessage(user_prompt)]
    )

    if not isinstance(ai_response, AIMessage) or not ai_response.tool_calls:
        return {"degraded": True, "degraded_reason": "investigate_model_no_tool_call"}

    tool_call = ai_response.tool_calls[0]

    try:
        envelope = await query_metrics.ainvoke(tool_call["args"])
    except (GatewayRequestError, BudgetExceededError) as e:
        return {"degraded": True, "degraded_reason": str(e)}

    return {
        "metrics_result": envelope["data"],
        "degraded": envelope["degraded"],
        "degraded_reason": envelope["degraded_reason"],
        "evidence": state.evidence
        + [{"tool": "query_metrics", "args": tool_call["args"]}],
    }


workflow = StateGraph(State)
workflow.add_node("investigate_once", investigate_once)
workflow.add_edge(START, "investigate_once")
workflow.add_edge("investigate_once", END)

graph = workflow.compile()
