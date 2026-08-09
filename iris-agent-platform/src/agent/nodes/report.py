"""Report node implementation."""

from __future__ import annotations

import structlog

from agent.state import IncidentState


async def report_once(state: IncidentState) -> None:
    """Generate a report based on the state."""
    logger = structlog.getLogger()
    logger.info(
        "report_once",
        investigate_rounds=state.get("investigate_rounds"),
        verify_verdict=state.get("verify_verdict"),
        budget_exhausted=(state.get("budget") or {}).get("budget_exhausted"),
    )
