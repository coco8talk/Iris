import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field

EvidenceKind = Literal["M", "L", "T", "S"]

_INCIDENT_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_DEFAULT_ROOT = Path(__file__).resolve().parents[2] / "runs" / "incidents"
_KIND_DIRS: dict[str, str] = {"M": "metrics", "L": "logs", "T": "traces", "S": "static"}
_EVIDENCE_ID_STRICT_RE = re.compile(r"^EV-([MLTS])-(\d{3})$")
EVIDENCE_ID_RE = re.compile(r"EV-[MLTS]-\d{3}")


class EvidenceStore:
    """单个 incident 的证据台账,目录格式即契约(执行计划 §0.6),T35/T38/T39 共同依赖。

    只允许读写自己 incident 目录内的文件;incident_id 经白名单校验,杜绝路径穿越。
    """

    def __init__(self, incident_id: str, root: Path | None = None) -> None:
        if not _INCIDENT_ID_RE.match(incident_id):
            raise ValueError(f"invalid incident_id: {incident_id!r}")
        self.incident_id = incident_id
        self._dir = (root or _DEFAULT_ROOT) / incident_id

    @property
    def incident_dir(self) -> Path:
        return self._dir

    def new_id(self, kind: EvidenceKind) -> str:
        kind_dir = self._dir / "evidence" / _KIND_DIRS[kind]
        number = [
            int(p.stem.rsplit("-", 1)[1])
            for p in kind_dir.glob(f"EV-{kind}-*.md")
        ] if kind_dir.exists() else []
        return f"EV-{kind}-{max(number, default=0) + 1:03d}"

    def write(
            self,
            evidence_id: str,
            source: str,
            agent_role: str,
            degraded: bool,
            summary: str,
            excerpt: str) -> Path:
        path = self._path_for(evidence_id)
        if path.exists():
            raise FileExistsError(f"evidence already recorded: {evidence_id}")
        path.parent.mkdir(parents=True, exist_ok=True)
        ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
        path.write_text(
            "---\n"
            f"evidence_id: {evidence_id}\n"
            f"source: {json.dumps(source, ensure_ascii=False)}\n"
            f"agent_role: {agent_role}\n"
            f"ts: {ts}\n"
            f"degraded: {str(degraded).lower()}\n"
            "---\n\n"
            f"## 摘要\n\n{summary.strip()}\n\n"
            f"## 关键数据摘录\n\n{excerpt.strip()}\n",
            encoding="utf-8",
        )
        return path

    def exists(self, evidence_id: str) -> bool:
        try:
            return self._path_for(evidence_id).exists()
        except ValueError:
            return False

    def list_ids(self) -> list[str]:
        ev_dir = self._dir / "evidence"
        if not ev_dir.exists():
            return []
        return sorted(p.stem for p in ev_dir.glob("*/EV-*.md"))

    def read(self, evidence_id: str) -> str:
        return self._path_for(evidence_id).read_text(encoding="utf-8")

    def write_alert(self, alerts: object) -> Path:
        self._dir.mkdir(parents=True, exist_ok=True)
        path = self._dir / "alerts.json"
        path.write_text(json.dumps(alerts, indent=2, ensure_ascii=False), encoding="utf-8")
        return path

    def write_report_draft(self, markdown: str) -> Path:
        self._dir.mkdir(parents=True, exist_ok=True)
        path = self._dir / "report_draft.md"
        path.write_text(markdown, encoding="utf-8")
        return path

    def _path_for(self, evidence_id: str) -> Path:
        match = _EVIDENCE_ID_STRICT_RE.match(evidence_id)
        if not match:
            raise ValueError(f"Invalid evidence id: {evidence_id!r}")
        return (
                self._dir / "evidence" / _KIND_DIRS[match.group(1)] / f"{evidence_id}.md"
        )


_RECORD_EVIDENCE_DESCRIPTION = (
    "把刚获得的关键取证结果登记为一条证据,返回证据编号(如 EV-M-001)。"
    "每次工具查询得到支撑或排除假设的结果后,必须立即调用本工具登记;"
    "报告中只允许引用本工具返回的编号。"
)


class RecordEvidenceArgs(BaseModel):
    kind: Literal["M", "L", "T", "S"] = Field(
        description="证据类别:M=指标 L=日志 T=链路 S=静态事实(CMDB/变更)"
    )
    source: str = Field(
        description="可回放的工具调用描述,如 query_metrics(template=p99_latency, service=order-service, window=30m)"
    )
    summary: str = Field(description="不超过 3 句的结论性摘要")
    excerpt: str = Field(description="支撑摘要的关键原始数据摘录")
    degraded: bool = Field(
        default=False, description="该次取证是否处于降级(数据不完整)状态"
    )


def make_record_evidence_tool(
        store: EvidenceStore, agent_role: str = "lead"
) -> StructuredTool:
    def _record(
            kind: Literal["M", "L", "T", "S"],
            source: str,
            summary: str,
            excerpt: str,
            degraded: bool = False,
    ) -> str:
        evidence_id = store.new_id(kind)
        store.write(
            evidence_id,
            source=source,
            agent_role=agent_role,
            degraded=degraded,
            summary=summary,
            excerpt=excerpt,
        )
        return evidence_id

    return StructuredTool.from_function(
        func=_record,
        name="record_evidence",
        description=_RECORD_EVIDENCE_DESCRIPTION,
        args_schema=RecordEvidenceArgs,
    )
