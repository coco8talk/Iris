"""Verify node implementation and routing."""

from __future__ import annotations

from typing import Any, Literal

import structlog

from agent.config import get_settings
from agent.state import IncidentState

logger = structlog.getLogger()


async def verify_once(state: IncidentState) -> dict[str, Any]:
    """M9 的确定性预检那一半：不调 LLM，纯规则判断证据够不够."""
    incident_id = state.get("incident_id")
    if not state.get("metrics_result") or state.get("degraded"):
        logger.warning("verify_fail", incident_id=incident_id, reason="未取到有效指标证据")
        return {
            "verify_verdict": {
                "verdict": "fail",
                "objections": ["未取到有效指标证据"],
            },
            "verifier_feedback": ["未取到有效指标证据"],
        }
    logger.info("verify_pass", incident_id=incident_id)
    return {"verify_verdict": {"verdict": "pass"}}


async def route_after_verify(
    state: IncidentState,
) -> Literal["report_once", "investigate_once"]:
    """Verify 之后决定回流还是收口；只读不写 state（rounds 的 +1 在 investigate_once 里）."""
    verdict = (state.get("verify_verdict") or {}).get("verdict")
    max_investigate_rounds = get_settings().max_investigate_rounds
    incident_id = state.get("incident_id")

    if verdict == "pass":
        route = "report_once"
    # 预算/墙钟已经触顶，再回流也只会立刻被 check_budget 拦下，直接出报告
    elif (state.get("budget") or {}).get("budget_exhausted"):
        route = "report_once"
    elif (
        verdict == "fail"
        and state.get("investigate_rounds", 0) < max_investigate_rounds
    ):
        route = "investigate_once"
    else:
        route = "report_once"

    logger.info(
        "route_after_verify",
        incident_id=incident_id,
        verdict=verdict,
        investigate_rounds=state.get("investigate_rounds", 0),
        route=route,
    )
    return route
