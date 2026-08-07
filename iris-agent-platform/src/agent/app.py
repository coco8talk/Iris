import json
import traceback
from asyncio import Task, create_task
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from agent.graph import build_graph
from agent.store import mark_status, create_db_and_tables


async def _drain(tasks, timeout):
    pass


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with AsyncSqliteSaver.from_conn_string("runs/checkpoints.db") as saver:
        create_db_and_tables()
        app.state.graph = build_graph(checkpointer=saver)
        app.state.tasks: dict[Task, str] = {}         # 持有后台任务引用，防被 GC
        yield
        # 退出：停收新任务 → 等在途任务收尾（超时就 cancel）
        await _drain(app.state.tasks, timeout=30)


app = FastAPI(lifespan=lifespan)

def spawn_incident_run(app: FastAPI, incident_id: str, initial_state: dict) -> None:
    async def _run():
        config = {"configurable": {"thread_id": incident_id}}
        try:
            await app.state.graph.ainvoke(initial_state, config)
            mark_status(incident_id, "closed")
        except Exception:
            mark_status(incident_id, "failed", last_error=traceback.format_exc())

    task = create_task(_run())
    app.state.tasks[task] = incident_id
    task.add_done_callback(lambda t: app.state.tasks.pop(t, None))

def resume_incident_run(app: FastAPI, incident_id: str) -> None:
    async def _run():
        config = {"configurable": {"thread_id": incident_id}}
        try:
            await app.state.graph.ainvoke(None, config)
            mark_status(incident_id, "closed")
        except Exception:
            mark_status(incident_id, "failed", last_error=traceback.format_exc())

    task = create_task(_run())
    app.state.tasks[task] = incident_id
    task.add_done_callback(lambda t: app.state.tasks.pop(t, None))

@app.post("/webhook/alertmanager")
async def webhook(request: Request) -> JSONResponse:
    body = await request.json()
    print(json.dumps(body, indent=2, ensure_ascii=False))
    with open("am-fixture.json", "w") as file:
        json.dump(body, file, indent=2, ensure_ascii=False)
        state = {"incident_id": "inc-test-001d", "service": "pm-auth"}
        spawn_incident_run(app, state["incident_id"], state)
    return JSONResponse({"ok": True})



@app.post("/incidents/{incident_id}/resume")
async def resume(incident_id: str):
    resume_incident_run(app, incident_id)
    return JSONResponse({"resumed": True})