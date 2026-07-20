"""POST /report:接收告警 → 调用 investigator → 返回 RcaReport。"""
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from langchain_core.language_models import BaseChatModel
from pydantic import BaseModel, Field

from sre_copilot.agents.evidence import EvidenceStore
from sre_copilot.agents.simple_investigator import investigate
from sre_copilot.graph.state import RcaReport
from sre_copilot.tools.client import GatewayClient
from sre_copilot.tools.definitions import make_tools

router = APIRouter()


class AlertRequest(BaseModel):
    """告警请求体 —— FastAPI 依据它自动做 422 校验。"""
    alertname: str = Field(description="告警名,必填")
    labels: dict[str, str] = Field(default_factory=dict)
    annotations: dict[str, str] = Field(default_factory=dict)
    startsAt: str | None = None
    incident_id: str | None = Field(default=None, description="可选;不传则服务端生成")


class ReportResponse(BaseModel):
    """POST /report 的响应契约:事件号 + 结构化 RCA 报告。"""
    incident_id: str
    report: RcaReport


def get_model(request: Request) -> BaseChatModel:
    """依赖:从当前处理请求的 app 取共享模型(考点①取用侧)。"""
    return request.app.state.model


@router.post("/report", response_model=ReportResponse)
async def report(
    alert: AlertRequest,
    model: BaseChatModel = Depends(get_model),
) -> ReportResponse:
    """考点②:async endpoint 里调用(同步阻塞的)Agent,并映射 HTTP 错误语义。"""
    incident_id = alert.incident_id or f"inc-{uuid.uuid4().hex[:8]}"

    # 非法 incident_id 会抛 ValueError → 显式映射 400(结构合法但语义非法)。
    # 放在建 client 之前,避免 client 未赋值时 finally 里 close() 触发 NameError。
    try:
        evidence_store = EvidenceStore(incident_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"invalid incident_id: {exc}")

    # GatewayClient 头带 incident_id,天然每请求一份;缺配置时抛错未捕获即 500。
    client = GatewayClient(incident_id, "lead")
    try:
        tools = make_tools(client)
        # investigate 是同步阻塞调用!必须卸载到线程池,否则卡死事件循环。
        report_result = await run_in_threadpool(
            investigate,
            alert.model_dump(exclude={"incident_id"}),
            model,
            tools,
            evidence_store,
        )
        return ReportResponse(incident_id=incident_id, report=report_result)
    except HTTPException:
        raise  # 已是 HTTP 语义,原样抛出
    except Exception as exc:  # investigate 内部已兜底,这里是兜底的兜底
        raise HTTPException(status_code=500, detail=f"internal error: {type(exc).__name__}")
    finally:
        client.close()  # client 一定已赋值,可安全关闭
