import re
from datetime import timezone, datetime
from pathlib import Path
from typing import Literal

from agent.paths import incident_dir

_VALID_KINDS = ("M", "L", "T", "S")


class EvidenceStore:
    """一次事故的证据台账，所有证据落在 runs/incidents/{incident_id}/evidence/ 下."""

    def __init__(self, incident_id: str) -> None:
        """基目录取自 M5 的 paths.incident_dir(incident_id)，路径安全由它保证."""
        self.incident_id = incident_id
        self.base_dir = incident_dir(incident_id) / "evidence"
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def new_id(self, kind: Literal["M", "L", "T", "S"]) -> str:
        """按类型分配下一个编号：M=metrics / L=logs / T=trace / S=其他(cmdb/changes).

        返回形如 'EV-M-001'。序号靠**扫描已有文件名**求最大值 +1，不能用内存计数器——
        进程重启（M5 的 kill-resume 是常态）后内存计数会归零，编号就乱了。
        """
        if kind not in _VALID_KINDS:
            raise ValueError(f"非法 kind: {kind!r}")

        pattern = re.compile(rf"^EV-{kind}-(\d{{3}})\.md$")
        max_n = 0
        for path in self.base_dir.glob(f"EV-{kind}-*.md"):
            m = pattern.match(path.name)
            if m:
                max_n = max(max_n, int(m.group(1)))
        evidence_id =  f"EV-{kind}-{max_n + 1:03d}"
        (self.base_dir / f"{evidence_id}.md").touch()
        return evidence_id

    def write(
        self,
        kind: Literal["M", "L", "T", "S"],
        source: str,        # 取证来源，形如 "query_metrics(template=error_rate,service=pm-question,window=1800)"
        agent_role: str,    # 取证者角色，M7 恒为 "investigate"；M10 起是各 subagent 的角色名
        degraded: bool,     # 网关信封的 degraded 原样带进来，不许省
        truncated: bool,    # 网关信封 meta.truncated 原样带进来，不许省
        summary: str,       # ≤3 句的结论式摘要（"pm-question 的 p99 在 10:12 从 80ms 涨到 2.1s"）
        excerpt: str,       # 关键数据摘录，保留能支撑 summary 的最小片段，不要贴整个响应
    ) -> Path:
        """分配新证据编号并落盘一份证据文件，返回文件路径。文件格式 = YAML frontmatter + 正文，见下.

        evidence_id 由内部 new_id(kind) 分配，不接受外部传入——避免调用方直接构造
        任意 evidence_id 字符串拼进文件路径（那类校验交给 new_id 的 kind 类型约束）。
        """
        evidence_id = self.new_id(kind)
        path = self.base_dir / f"{evidence_id}.md"

        ts = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        content = (
            "---\n"
            f"evidence_id: {evidence_id}\n"
            f"source: {source}\n"
            f"agent_role: {agent_role}\n"
            f"ts: {ts}\n"
            f"degraded: {str(degraded).lower()}\n"
            f"truncated: {str(truncated).lower()}\n"
            "---\n\n"
            f"{summary}\n\n"
            f"{excerpt}\n"
        )
        path.write_text(content, encoding="utf-8")
        return path

    def list_ids(self) -> list[str]:
        """列出本事故已有的全部证据编号，M9 的确定性预检和 M10 的 lead 摘要工具都用它."""
        return [path.stem for path in self.base_dir.glob("EV-*.md")]

    def read(self, evidence_id: str) -> str:
        """读一份证据全文。M9 verify 的 LLM 段靠它拿到证据原文.

        evidence_id 这里是外部传入的（来自 RcaReport.evidence_ids，LLM 生成），
        必须校验，不能假设它一定是合法格式——write() 不再接受外部 evidence_id 后，
        这里是当前唯一还会把外部字符串拼进文件路径的地方。
        """
        path = (self.base_dir / f"{evidence_id}.md").resolve()
        if not path.is_relative_to(self.base_dir.resolve()):
            raise ValueError(f"evidence_id 越界: {evidence_id!r}")
        return path.read_text(encoding="utf-8")

