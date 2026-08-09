"""LangGraph single-node graph template.

Returns a predefined response. Replace logic and configuration as needed.
"""

from __future__ import annotations

import structlog
from langgraph.constants import END, START
from langgraph.graph import StateGraph
from langgraph.types import Checkpointer

from agent.config import AgentRole
from agent.nodes.investigate import make_investigate_node
from agent.nodes.report import report_once
from agent.nodes.triage import make_triage_node
from agent.nodes.verify import route_after_verify, verify_once
from agent.router import get_model
from agent.state import IncidentState, TriageResult

logger = structlog.getLogger()


# PyCharm cannot currently match LangGraph's structural StateLike protocols to TypedDict.
# noinspection PyTypeChecker
def build_graph(
    checkpointer: Checkpointer = None,
):
    """装配节点与边并编译。checkpointer=None 时交给托管方自己管 persistence."""
    workflow = StateGraph(IncidentState)
    triage_model = get_model(AgentRole.TRIAGE).with_structured_output(TriageResult)
    investigate_model = get_model(AgentRole.INVESTIGATE)
    workflow.add_node("triage", make_triage_node(triage_model))
    workflow.add_node("investigate_once", make_investigate_node(investigate_model))
    workflow.add_node("verify_once", verify_once)
    workflow.add_node("report_once", report_once)

    workflow.add_edge(START, "triage")
    workflow.add_edge("triage", "investigate_once")
    workflow.add_conditional_edges("verify_once", route_after_verify)
    workflow.add_edge("report_once", END)

    # checkpointer=None 意味着这次编译不落检查点、resume 端点也救不回来，
    # 而这恰好是 import agent 时模块级 build_graph() 的默认走法，值得一眼看见。
    logger.info(
        "graph_built",
        checkpointer=type(checkpointer).__name__ if checkpointer else None,
    )
    return workflow.compile(checkpointer=checkpointer)


graph = build_graph()
