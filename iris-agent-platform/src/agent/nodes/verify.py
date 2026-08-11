"""Verify node implementation and routing."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Literal

import structlog

from agent.config import get_settings
from agent.evidence import EvidenceStore
from agent.state import IncidentState, Verdict, VerdictEnum

logger = structlog.getLogger()


async def verify_once(state: IncidentState) -> dict[str, Any]:
    """M9 的确定性预检那一半：不调 LLM，纯规则判断证据够不够."""
    incident_id = state.get("incident_id")

    # 第 1 关：必须取到未降级的指标证据。
    if not state.get("metrics_result") or state.get("degraded"):
        verdict = Verdict(
            verdict=VerdictEnum.FAIL,
            objections=["未取到有效指标证据"],
        )
        logger.warning(
            "verify_fail",
            incident_id=incident_id,
            reason="未取到有效指标证据",
            objections=verdict.objections,
        )
        return {
            "verify_verdict": verdict,
            "verifier_feedback": verdict.objections,
        }

    # 第 2 关：草稿引用的每一条证据都必须真实存在。
    draft_report_ref = state["draft_report_ref"]
    assert draft_report_ref is not None
    draft_text = Path(draft_report_ref).read_text(encoding="utf-8")
    referenced_ids = set(re.findall(r"EV-[MLTS]-\d{3}", draft_text))
    existing_ids = set(EvidenceStore(state["incident_id"]).list_ids())

    if not referenced_ids:
        verdict = Verdict(
            verdict=VerdictEnum.FAIL,
            objections=["结论未引用任何证据"],
        )
        logger.warning(
            "verify_fail",
            incident_id=incident_id,
            reason="结论未引用任何证据",
            objections=verdict.objections,
        )
        return {
            "verify_verdict": verdict,
            "verifier_feedback": verdict.objections,
        }

    missing = referenced_ids - existing_ids
    if missing:
        verdict = Verdict(
            verdict=VerdictEnum.FAIL,
            objections=[f"引用的证据 {eid} 不存在" for eid in sorted(missing)],
        )
        logger.warning(
            "verify_fail",
            incident_id=incident_id,
            reason=verdict.objections,
            objections=verdict.objections,
        )
        return {
            "verify_verdict": verdict,
            "verifier_feedback": verdict.objections,
        }

    # 第 3 关（跨家族 LLM 核验）留给后续任务。
    verdict = Verdict(verdict=VerdictEnum.PASS)
    logger.info("verify_pass", incident_id=incident_id)
    return {"verify_verdict": verdict}


async def route_after_verify(
    state: IncidentState,
) -> Literal["report_once", "investigate_once"]:
    """Verify 之后决定回流还是收口；只读不写 state（rounds 的 +1 在 investigate_once 里）."""
    verdict_obj = state.get("verify_verdict")
    verdict = verdict_obj.verdict if verdict_obj else None
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
