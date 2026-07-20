"""API 契约 + 错误路径单测:TestClient + FakeListChatModel,不打真实模型/网关。"""
import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import FakeListChatModel

from sre_copilot.api.report import get_model
from sre_copilot.app import create_app

_VALID_ALERT = {
    "alertname": "OrderP99LatencyHigh",
    "labels": {"service": "order-service", "severity": "P2"},
    "annotations": {"summary": "order-service p99 latency > 2s for 5m"},
}


@pytest.fixture()
def client(monkeypatch, tmp_path) -> TestClient:
    # GatewayClient 会读 TOOL_GATEWAY_ 配置;测试里塞 dummy,构造不依赖真实 .env。
    monkeypatch.setenv("TOOL_GATEWAY_BASE_URL", "http://gateway.invalid")
    monkeypatch.setenv("TOOL_GATEWAY_TOKEN", "dummy-token")
    # 把证据落盘目录改到临时目录,避免单测污染 runs/incidents/。
    monkeypatch.setattr(
        "sre_copilot.agents.evidence._DEFAULT_ROOT", tmp_path / "incidents"
    )
    app = create_app()
    # 用 dependency_overrides 把真实模型换成假模型;不用 with,故 lifespan 不执行,零真实外呼。
    app.dependency_overrides[get_model] = lambda: FakeListChatModel(responses=["OK"])
    return TestClient(app)


def test_report_ok_contract(client: TestClient) -> None:
    """合法告警 → 200,响应符合 ReportResponse/RcaReport 结构。"""
    resp = client.post("/report", json=_VALID_ALERT)
    assert resp.status_code == 200
    body = resp.json()
    assert "incident_id" in body
    report = body["report"]
    for key in ("root_service", "root_cause", "confidence", "evidence_ids", "summary_md"):
        assert key in report


def test_missing_alertname_returns_422(client: TestClient) -> None:
    """缺 alertname → FastAPI 自动 422(无需自己写校验)。"""
    resp = client.post("/report", json={"labels": {}})
    assert resp.status_code == 422


def test_bad_incident_id_returns_400(client: TestClient) -> None:
    """结构合法但 incident_id 非法(含斜杠)→ 显式 400,而非 500。"""
    bad = {**_VALID_ALERT, "incident_id": "bad/../id"}
    resp = client.post("/report", json=bad)
    assert resp.status_code == 400


def test_healthz(client: TestClient) -> None:
    resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}
