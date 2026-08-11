"""Report node implementation."""

from __future__ import annotations

import json
from pathlib import Path

import structlog
from langchain_core.messages import HumanMessage

from agent.config import AgentRole, get_settings
from agent.llm_retry import ainvoke_with_retry
from agent.paths import incident_dir
from agent.report_template import render
from agent.router import get_model
from agent.state import IncidentState, RcaReport
from agent.store import mark_status

logger = structlog.getLogger()


async def report_once(state: IncidentState) -> None:
    """把排查结论渲染成最终报告、落盘，并把事故收口为 closed.

    用 LLM 只润色摘要，其余字段交给确定性模板渲染；用 `state.get()` 而不是
    `state["..."]`，因为 investigate 有两条路径会直接跳到这个节点却不写
    draft_report_ref/verify_verdict：缺 service/incident_id 提前短路
    （investigate.py 的 no_service/no_incident_id 分支），以及预算在拿到
    结论前就耗尽（BudgetExceededError/BudgetExhaustedError 分支）。这两种
    都是设计内的降级路径，不是 bug，必须也能产出报告、也能收口。
    """
    incident_id = state["incident_id"]  # 入口就写死的字段，缺了才是真 bug，值得直接崩
    verdict_obj = state.get("verify_verdict")
    verdict = verdict_obj.model_dump() if verdict_obj else {}
    budget = state.get("budget") or {}
    rounds_exhausted = (
        state.get("investigate_rounds", 0) >= get_settings().max_investigate_rounds
    )

    draft_ref = state.get("draft_report_ref")
    if draft_ref is not None:
        draft = RcaReport.model_validate_json(Path(draft_ref).read_text(encoding="utf-8"))
    else:
        reason = state.get("degraded_reason") or "unknown"
        draft = RcaReport(
            root_service=state.get("service") or "unknown",
            root_cause="investigation_incomplete",
            root_detail=f"排查未能产出结论：{reason}",
            confidence="low",
            causal_chain=[],
            evidence_ids=state.get("evidence") or [],
            summary_md=f"本次事故排查未能完成（{reason}），无法给出根因结论，需人工介入。",
        )

    prompt = f"""请润色以下事故报告摘要。
只改善中文表达的流畅度和可读性，不能改变任何事实、结论、数字、服务名或其他内容。
只输出润色后的摘要原文，不要用 Markdown 代码块包裹，不要添加任何前缀或后缀说明。

原文：
{draft.summary_md}"""
    polish_failed = False
    try:
        model = get_model(AgentRole.REPORT)
        response = await ainvoke_with_retry(
            model,
            [HumanMessage(content=prompt)],
            incident_id=incident_id,
            agent_role="report",
        )
        llm_text = response.content
        polished = draft.model_copy(update={"summary_md": llm_text})
    except Exception as exc:
        logger.warning(
            "report_polish_failed",
            incident_id=incident_id,
            error_type=type(exc).__name__,
            error=str(exc),
        )
        polished = draft
        polish_failed = True

    report_str = render(polished, verdict, budget, rounds_exhausted)
    if polish_failed:
        report_str += "\n摘要由模板生成\n"

    output_dir = incident_dir(incident_id)
    (output_dir / "report.md").write_text(report_str, encoding="utf-8")
    report_data = polished.model_dump()
    report_data.update(
        {
            "verdict": verdict,
            "objections": verdict.get("objections") or [],
            "budget_exhausted": bool(budget.get("budget_exhausted", False)),
        }
    )
    (output_dir / "report.json").write_text(
        json.dumps(report_data, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    mark_status(incident_id, "closed")

    logger.info(
        "report_once",
        incident_id=incident_id,
        investigate_rounds=state.get("investigate_rounds"),
        verdict=verdict.get("verdict"),
        budget_exhausted=budget.get("budget_exhausted"),
        had_draft=draft_ref is not None,
    )
