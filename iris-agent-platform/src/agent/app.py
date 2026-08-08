"""FastAPI entry points for receiving and running incident workflows."""

import json
import time
import traceback
from asyncio import Task, create_task, wait
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from agent.alert import fingerprint_of, new_incident_id, parse_am_payload
from agent.graph import build_graph
from agent.paths import write_alert_fixture
from agent.state import IncidentState
from agent.store import (
    create_db_and_tables,
    create_incident,
    find_active,
    mark_status,
    merge_alert,
)

TaskRegistry = dict[Task[None], str]


async def _drain(tasks: TaskRegistry, timeout: float) -> None:
    if not tasks:
        return
    _, pending = await wait(tasks, timeout=timeout)
    for task in pending:
        incident_id = tasks.get(task)
        task.cancel()
        if incident_id:
            mark_status(incident_id, "failed", last_error="cancelled on shutdown")


@asynccontextmanager
async def lifespan(fastapi_app: FastAPI) -> AsyncIterator[None]:
    """Initialize persistence and drain background workflows on shutdown."""
    async with AsyncSqliteSaver.from_conn_string("runs/checkpoints.db") as saver:
        create_db_and_tables()
        fastapi_app.state.graph = build_graph(checkpointer=saver)
        tasks: TaskRegistry = {}
        fastapi_app.state.tasks = tasks  # 持有后台任务引用，防被 GC
        fastapi_app.state.accepting = True
        yield
        fastapi_app.state.accepting = False
        # 退出：停收新任务 → 等在途任务收尾（超时就 cancel）
        await _drain(tasks, timeout=30)


app = FastAPI(lifespan=lifespan)


def spawn_incident_run(
    fastapi_app: FastAPI, incident_id: str, initial_state: IncidentState
) -> None:
    """Start a new incident workflow and retain its background task."""

    async def _run() -> None:
        config = {"configurable": {"thread_id": incident_id}}
        # noinspection PyBroadException
        try:
            await fastapi_app.state.graph.ainvoke(initial_state, config)
            mark_status(incident_id, "closed")
        except Exception:
            mark_status(incident_id, "failed", last_error=traceback.format_exc())

    task = create_task(_run())
    fastapi_app.state.tasks[task] = incident_id
    task.add_done_callback(lambda t: fastapi_app.state.tasks.pop(t, None))


def resume_incident_run(fastapi_app: FastAPI, incident_id: str) -> None:
    """Resume a checkpointed incident workflow in a background task."""

    async def _run() -> None:
        config = {"configurable": {"thread_id": incident_id}}
        # noinspection PyBroadException
        try:
            await fastapi_app.state.graph.ainvoke(None, config)
            mark_status(incident_id, "closed")
        except Exception:
            mark_status(incident_id, "failed", last_error=traceback.format_exc())

    task = create_task(_run())
    fastapi_app.state.tasks[task] = incident_id
    task.add_done_callback(lambda t: fastapi_app.state.tasks.pop(t, None))


# "deadletter" is the established directory and response-key terminology.
# noinspection SpellCheckingInspection
@app.post("/webhook/alertmanager")
async def webhook(request: Request) -> JSONResponse:
    """Accept an Alertmanager payload and start or merge an incident."""
    if not app.state.accepting:
        return JSONResponse({"error": "shutting down"}, status_code=503)
    body_bytes = await request.body()
    try:
        payload = json.loads(body_bytes)
        alerts = parse_am_payload(payload)
        fingerprint = fingerprint_of(alerts)
        incident_id = find_active(fingerprint)
        if incident_id is not None:
            mark_status(incident_id, "running")
            merge_alert(incident_id)
            return JSONResponse({"ok": True})
        incident_id = new_incident_id()
        alert_ref = write_alert_fixture(incident_id, payload)
        create_incident(incident_id, fingerprint, alert_ref)
        initial_state: IncidentState = {
            "incident_id": incident_id,
            "service": alerts[0].service,
            "fingerprint": fingerprint,
            "alerts_ref": alert_ref,
        }
        spawn_incident_run(app, incident_id, initial_state)
        return JSONResponse({"ok": True})
    # noinspection PyBroadException
    except Exception as exc:
        deadletter_dir = Path("runs/deadletter")
        deadletter_dir.mkdir(parents=True, exist_ok=True)
        name = f"{int(time.time() * 1000)}-{uuid4().hex[:8]}"
        (deadletter_dir / f"{name}.json").write_bytes(body_bytes)
        (deadletter_dir / f"{name}.err").write_text(
            f"{type(exc).__name__}: {exc}\n{traceback.format_exc()}"
        )
        return JSONResponse(
            {"deadlettered": True, "file": str(deadletter_dir / f"{name}.json")}
        )


@app.post("/incidents/{incident_id}/resume")
async def resume(incident_id: str) -> JSONResponse:
    """Resume a previously checkpointed incident workflow."""
    resume_incident_run(app, incident_id)
    return JSONResponse({"resumed": True})
