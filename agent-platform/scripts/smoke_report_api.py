"""阶段 6 真实冒烟:启动 Uvicorn,用 httpx 打 POST /report,校验真实模型+Gateway 链路。

红线-5:真连大模型与 Gateway,不得用脚本直调 Agent、不得用 FakeListChatModel 替代。
运行(项目根为 agent-platform,需 .env 里模型三件套 + TOOL_GATEWAY_* 齐全,且 tool-gateway 已运行):

    uv run python scripts/smoke_report_api.py

可选环境变量:
    SMOKE_PORT           冒烟服务端口,默认 8077(避开手动起的 8000)
    SMOKE_REUSE_SERVER=1 复用已在 SMOKE_PORT 上运行的服务,不自己拉起 Uvicorn
    TOOL_GATEWAY_AUDIT_DB 审计库路径,默认 <repo>/data/tool-gateway.db
"""
import json
import os
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

import httpx

_PROJECT_ROOT = Path(__file__).resolve().parents[1]
_REPO_ROOT = _PROJECT_ROOT.parent
_DEFAULT_AUDIT_DB = _REPO_ROOT / "data" / "tool-gateway.db"

_HOST = "127.0.0.1"
_PORT = int(os.environ.get("SMOKE_PORT", "8077"))
_BASE_URL = f"http://{_HOST}:{_PORT}"

_QUERY_ENDPOINTS = {
    "/tools/query_cmdb",
    "/tools/query_changes",
    "/tools/query_metrics",
    "/tools/query_logs",
    "/tools/query_trace",
}

# F01 fixture:inventory-service 慢 SQL/缺索引,症状表现为上游 order-service 延迟。
_F01_ALERT = {
    "alertname": "OrderP99LatencyHigh",
    "labels": {"service": "order-service", "severity": "P2"},
    "annotations": {
        "summary": "order-service p99 latency > 2s for 5m",
        "description": "下单接口整体变慢,数据库相关指标疑似异常(fixture,对应故障 F01)",
    },
}


def wait_for_healthz(timeout_s: float = 60.0) -> bool:
    """轮询 /healthz 直到 200 或超时。"""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            resp = httpx.get(f"{_BASE_URL}/healthz", timeout=2.0)
            if resp.status_code == 200 and resp.json().get("status") == "ok":
                return True
        except httpx.HTTPError:
            pass
        time.sleep(0.5)
    return False


def read_audit_rows(incident_id: str) -> list[tuple[str, int, int]]:
    db_path = Path(os.environ.get("TOOL_GATEWAY_AUDIT_DB", _DEFAULT_AUDIT_DB))
    if not db_path.exists():
        print(f"WARN audit db not found at {db_path}", file=sys.stderr)
        return []
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute(
            "select endpoint, status, degraded from audit_log where incident_id = ?",
            (incident_id,),
        ).fetchall()
    for endpoint, status, degraded in rows:
        print(f"audit endpoint={endpoint} status={status} degraded={degraded}")
    return rows


def run_checks() -> bool:
    ok = True

    def check(name: str, passed: bool, detail: str) -> None:
        nonlocal ok
        print(f"{'PASS' if passed else 'FAIL'} check={name} {detail}")
        ok = ok and passed

    with httpx.Client(base_url=_BASE_URL, timeout=180.0) as http:
        # 1) OpenAPI 可见 /report 与 /healthz
        spec = http.get("/openapi.json").json()
        paths = set(spec.get("paths", {}))
        check("openapi_paths", {"/report", "/healthz"} <= paths, f"paths={sorted(paths)}")

        # 2) 坏请求不假成功:缺 alertname → 422
        r422 = http.post("/report", json={"labels": {}})
        check("missing_alertname_422", r422.status_code == 422, f"status={r422.status_code}")

        # 3) 结构合法但语义非法:incident_id 含斜杠 → 400(而非 500)
        r400 = http.post("/report", json={"alertname": "x", "incident_id": "bad/../id"})
        check("bad_incident_id_400", r400.status_code == 400, f"status={r400.status_code}")

        # 4) 合法告警 → 200 真实报告(真连模型 + Gateway)
        resp = http.post("/report", json=_F01_ALERT)
        check("report_200", resp.status_code == 200, f"status={resp.status_code}")
        if resp.status_code != 200:
            print(resp.text[:500])
            return False

        body = resp.json()
        incident_id = body["incident_id"]
        report = body["report"]
        print(f"incident_id={incident_id}")

    # 5) 报告确实来自真实模型,不是确定性兜底
    fallback = "本报告由确定性模板生成" in report["summary_md"]
    check(
        "model_completed_report",
        not fallback,
        f"root_service={report['root_service']} degraded={report['degraded']} "
        f"reason={report['degraded_reason']}",
    )

    # 6) 证据被真实登记且引用真实存在
    check("evidence_recorded", len(report["evidence_ids"]) >= 1, f"evidence_ids={report['evidence_ids']}")

    incident_dir = _PROJECT_ROOT / "runs" / "incidents" / incident_id
    check("alerts_json", (incident_dir / "alerts.json").exists(), str(incident_dir / "alerts.json"))
    check("report_draft", (incident_dir / "report_draft.md").exists(), str(incident_dir / "report_draft.md"))

    # 7) 真实 Gateway 调用被审计(证明不是脱机造数据)
    audit_rows = read_audit_rows(incident_id)
    query_rows = [r for r in audit_rows if r[0] in _QUERY_ENDPOINTS]
    check("real_gateway_calls_audited", len(query_rows) >= 1, f"audit_rows={len(audit_rows)} query_rows={len(query_rows)}")

    # 8)(非降级时)F01 根因应为 inventory-service
    if not report["degraded"]:
        check(
            "f01_root_service",
            report["root_service"] == "inventory-service",
            f"root_service={report['root_service']}",
        )

    print("--- report ---")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return ok


def main() -> int:
    reuse = os.environ.get("SMOKE_REUSE_SERVER") == "1"
    proc: subprocess.Popen | None = None
    if reuse:
        print(f"reuse mode: expecting server at {_BASE_URL}")
    else:
        print(f"launching uvicorn on {_BASE_URL} ...")
        proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "sre_copilot.app:app",
             "--host", _HOST, "--port", str(_PORT)],
            cwd=str(_PROJECT_ROOT),
        )
    try:
        if not wait_for_healthz():
            print("FAIL server did not become healthy in time", file=sys.stderr)
            return 1
        return 0 if run_checks() else 1
    finally:
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
