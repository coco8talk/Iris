"""事故目录与告警落盘：incident_id 属于不可信输入，两层校验防路径穿越.

M7 的 EvidenceStore、M8 的报告落盘、M9 的 verdict 落盘都复用 incident_dir()，不要各写一份。
"""

import json
import re
from pathlib import Path
from typing import Any, List

import structlog

logger = structlog.getLogger()

RUNS_ROOT = Path("runs/incidents").resolve()
_INCIDENT_ID_RE = re.compile(r"^inc-\d{8}-\d{3}$")


def incident_dir(incident_id: str) -> Path:
    """返回 runs/incidents/{incident_id}/，不存在则创建.

    两层校验：
    1. 先用 _INCIDENT_ID_RE 白名单校验形状（只允许 inc-YYYYMMDD-NNN）；
    2. 再 resolve() 后确认结果目录确实在 RUNS_ROOT 之内，否则抛 ValueError。
    """
    # 这两条是安全边界，不是普通参数校验：incident_id 来自不可信输入，命中即
    # 意味着要么调用方传错了形状（上一轮 read_alert_fixture 就是这么炸的），
    # 要么有人在试路径穿越。两种都必须能在日志里查到，光抛异常不够。
    if not _INCIDENT_ID_RE.match(incident_id):
        logger.warning("incident_id_rejected", incident_id=incident_id, reason="bad_shape")
        raise ValueError(f"非法 incident_id: {incident_id!r}")

    candidate = (RUNS_ROOT / incident_id).resolve()
    if not candidate.is_relative_to(RUNS_ROOT):
        logger.error(
            "incident_id_rejected",
            incident_id=incident_id,
            reason="path_traversal",
            resolved=str(candidate),
        )
        raise ValueError(f"incident_id 越界: {incident_id!r}")

    candidate.mkdir(parents=True, exist_ok=True)
    return candidate


def write_alert_fixture(incident_id: str, alert_payload: dict) -> str:
    """把 Alertmanager 原始 payload 原样落盘成 runs/incidents/{id}/alert.json.

    落的是原样 payload 不是解析后的 Alert 列表——出问题时要能拿原文复现；
    返回相对仓库根的路径字符串，写进 IncidentState.alerts_ref（state 里只存这个
    返回值，不存 payload 全文，[R13]）。
    """
    path = incident_dir(incident_id) / "alert.json"
    path.write_text(
        json.dumps(alert_payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    return f"runs/incidents/{incident_id}/alert.json"


def read_alert_fixture(alert_ref: str) -> List[dict[str, Any]]:

    path = Path(alert_ref)
    alerts = json.loads(path.read_text(encoding="utf-8"))["alerts"]
    alert_list = []
    # alertname / service / labels / annotations
    for alert in alerts:
        labels = alert["labels"]
        alert_list.append(
            {
                "alertname": labels["alertname"],
                "service": labels["service"],
                "labels": labels,
                "annotations": alert["annotations"],
            }
        )
    return alert_list


