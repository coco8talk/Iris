"""SRE Copilot 的 HTTP 入口:把阶段 5 的 investigator 包成常驻 FastAPI 服务。"""
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from sre_copilot.api.report import router as report_router
from sre_copilot.config import create_openrouter_model


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    model = create_openrouter_model()
    app.state.model = model
    yield
    #本阶段模型无需显式 close


def create_app() -> FastAPI:
    """工厂函数:构造并返回 FastAPI 应用(便于测试注入/覆盖依赖)。"""
    app = FastAPI(title="SRE Copilot", version="0.6.0", lifespan=lifespan)
    app.include_router(report_router)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        """就绪探针:仅检查进程存活,不碰模型/网关(红线:仅进程基本状态)。"""
        return {"status": "ok"}

    return app


app = create_app()