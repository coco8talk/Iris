import json
import sqlite3
import sys
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from rich import print as rprint

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from langchain_core.messages import HumanMessage
from langchain_core.tracers.context import collect_runs

from sre_copilot.agents.evidence import EvidenceStore
from sre_copilot.agents.simple_investigator import investigate
from sre_copilot.config import create_alibaba_model
from sre_copilot.tools.client import GatewayClient
from sre_copilot.tools.definitions import make_tools

# 审计库路径:网关以仓库根为 cwd 运行时落在 <repo>/data/;可用环境变量覆盖。
_DEFAULT_AUDIT_DB = Path(__file__).resolve().parents[2] / "data" / "tool-gateway.db"

_QUERY_TOOLS = {
    "query_cmdb",
    "query_changes",
    "query_metrics",
    "query_logs",
    "query_trace",
}

# F01 fixture:inventory-service 慢 SQL/缺索引,症状表现为上游 order-service 延迟。
_F01_ALERT = {
    "alertname": "OrderP99LatencyHigh",
    "labels": {"service": "order-service", "severity": "P2"},
    "annotations": {
        "summary": "order-service p99 latency > 2s for 5m",
        "description": "下单接口整体变慢,数据库相关指标疑似异常(fixture,对应故障 F01)",
    },
    "startsAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
}


def check_model_gate() -> bool:
    """红线-5 门禁:先确认真实模型凭证有效,再进行完整诊断。"""
    started = time.perf_counter()
    try:
        model = create_alibaba_model()
        response = model.invoke([HumanMessage(content="Reply with the single word OK.")])
        if not response.text.strip():
            raise RuntimeError("empty model response")
        elapsed_ms = round((time.perf_counter() - started) * 1000)
        print(f"PASS gate=model model={model.model_name} elapsed_ms={elapsed_ms}")
        rprint(response)
        return True
    except Exception as exc:
        print(f"FAIL gate=model error={type(exc).__name__}: {exc}", file=sys.stderr)
        return False


def check_investigate(incident_id: str) -> bool:
    client = GatewayClient(incident_id=incident_id, agent_role="lead")
    store = EvidenceStore(incident_id)
    try:
        with collect_runs() as runs:
            report = investigate(
                alert=_F01_ALERT,
                model=create_alibaba_model(),
                tools=make_tools(client),
                evidence_store=store,
            )
    finally:
        client.close()

    ok = True

    def check(name: str, passed: bool, detail: str) -> None:
        nonlocal ok
        print(f"{'PASS' if passed else 'FAIL'} check={name} {detail}")
        ok = ok and passed

    traced_runs = runs.traced_runs
    llm_runs = [run for run in traced_runs if run.run_type == "llm"]
    tool_runs = [run for run in traced_runs if run.run_type == "tool"]
    tool_counts = Counter(run.name for run in tool_runs)
    tool_errors = [
        f"{run.name}: {run.error}"
        for run in tool_runs
        if run.error
    ]
    print(
        f"trace llm_rounds={len(llm_runs)} "
        f"tool_calls={dict(sorted(tool_counts.items()))}"
    )

    check("llm_multi_round", len(llm_runs) >= 2, f"llm_rounds={len(llm_runs)}")
    check("tool_runs_succeeded", not tool_errors, f"errors={tool_errors}")

    query_call_count = sum(tool_counts[name] for name in _QUERY_TOOLS)
    evidence_call_count = tool_counts["record_evidence"]
    check(
        "model_selected_query_tools",
        query_call_count >= 1,
        f"selected={sorted(name for name in _QUERY_TOOLS if tool_counts[name])} ",
    )
    check(
        "model_recorded_evidence",
        evidence_call_count >= 1,
        f"queries={query_call_count} record_evidence={evidence_call_count}",
    )

    incident_dir = store.incident_dir
    check(
        "alerts_json",
        (incident_dir / "alerts.json").exists(),
        str(incident_dir / "alerts.json"),
    )
    check(
        "report_draft",
        (incident_dir / "report_draft.md").exists(),
        str(incident_dir / "report_draft.md"),
    )

    recorded = store.list_ids()
    check(
        "evidence_files",
        len(recorded) >= 1,
        f"recorded={recorded} record_evidence={evidence_call_count}",
    )

    dangling = [eid for eid in report.evidence_ids if not store.exists(eid)]
    check("citations_exist", not dangling, f"cited={report.evidence_ids} dangling={dangling}")

    audit_rows = read_audit_rows(incident_id)
    check(
        "real_gateway_calls_audited",
        1 <= len(audit_rows) <= query_call_count,
        f"query_attempts={query_call_count} audit_rows={len(audit_rows)}",
    )
    bad_audit_rows = [
        row for row in audit_rows if row[1] != 200 or bool(row[2])
    ]
    if bad_audit_rows:
        print(f"NOTE gateway returned failed/degraded rows: {bad_audit_rows}")

    fallback = "本报告由确定性模板生成" in report.summary_md
    check(
        "model_completed_report",
        not fallback,
        f"root_service={report.root_service} degraded={report.degraded} "
        f"reason={report.degraded_reason}",
    )
    if not report.degraded:
        check(
            "f01_root_service",
            report.root_service == "inventory-service",
            f"root_service={report.root_service}",
        )

    print("--- report ---")
    print(json.dumps(report.model_dump(), ensure_ascii=False, indent=2))
    if report.degraded:
        print(f"NOTE report is degraded: {report.degraded_reason}")
    return ok


def read_audit_rows(incident_id: str) -> list[tuple[str, int, int]]:
    import os

    db_path = Path(os.environ.get("TOOL_GATEWAY_AUDIT_DB", _DEFAULT_AUDIT_DB))
    if not db_path.exists():
        print(
            f"WARN audit db not found at {db_path}; "
            "set TOOL_GATEWAY_AUDIT_DB or check manually with: "
            f'sqlite3 <db> "select endpoint,status,degraded from audit_log '
            f"where incident_id='{incident_id}'\"",
            file=sys.stderr,
        )
        return []
    with sqlite3.connect(db_path) as conn:
        rows = conn.execute(
            "select endpoint, status, degraded from audit_log where incident_id = ?",
            (incident_id,),
        ).fetchall()
    for endpoint, status, degraded in rows:
        print(f"audit endpoint={endpoint} status={status} degraded={degraded}")
    return rows


def main() -> int:
    if not check_model_gate():
        return 1
    incident_id = f"smoke-{uuid.uuid4().hex[:8]}"
    print(f"incident_id={incident_id}")
    return 0 if check_investigate(incident_id) else 1


if __name__ == "__main__":
    raise SystemExit(main())
