"""Alertmanager payload parsing and incident identifier helpers."""

import hashlib
from datetime import UTC, datetime
from typing import Any

import structlog
from pydantic import BaseModel

from agent.store import next_daily_seq

logger = structlog.getLogger()


class Alert(BaseModel):
    """一条 Alertmanager 告警，字段全部来自 AM v4 负载，不做二次加工."""

    alertname: str  # labels.alertname，告警规则名（M1 的 alert-rules.yaml 里定义）
    service: str  # labels.service，告警指向的服务名，对齐 prunus-mume 真实 application 值 [R3]
    severity: str  # labels.severity，AM 侧的严重度标签，注意它不等于 M6 triage 判出来的 severity
    status: str  # "firing" | "resolved"
    starts_at: datetime  # startsAt，告警开始时间
    labels: dict[str, str]  # 完整 labels 原样保留，M6 的 triage prompt 会用到
    annotations: dict[str, str]  # summary/description 等，M6 的 triage prompt 会用到
    fingerprint: str  # AM 自带的 fingerprint，去重的第一优先来源


def parse_am_payload(payload: dict[str, Any]) -> list[Alert]:
    """把 AM v4 负载解析成 Alert 列表；任一必需字段缺失就抛，由 T5.3 的死信兜底接住."""
    alerts: list[Alert] = []
    for alert in payload["alerts"]:
        labels = alert["labels"]
        alerts.append(
            Alert(
                alertname=labels["alertname"],
                service=labels["service"],
                severity=labels["severity"],
                status=alert["status"],
                starts_at=alert["startsAt"],
                labels=labels,
                annotations=alert["annotations"],
                fingerprint=alert["fingerprint"],
            )
        )
    return alerts


def fingerprint_of(alerts: list[Alert]) -> str:
    """取本组告警的指纹.

    1. 优先用 alerts[0].fingerprint（AM 自带，稳定）；
    2. 缺失时回退自算 sha256(alertname + service + 排序后的关键 labels)[:16]；
    3. 回退算法里**不许**掺入 starts_at 等时间类 label，否则每条都算"不同"永远去不掉重。
    """
    alert = alerts[0]
    fingerprint = alert.fingerprint
    if fingerprint:
        return fingerprint

    # 走到回退算法说明 AM 没带 fingerprint。回退指纹和 AM 原生指纹算出来的值
    # 不同，同一个事故若前后两次分走两条路径就会去重失败、拆成两个 incident，
    # 这是排查“为什么重复建单”时的第一现场。
    logger.warning(
        "fingerprint_fallback",
        alertname=alert.alertname,
        service=alert.service,
    )

    raw = (
        alert.alertname
        + alert.service
        + "".join(sorted([f"{key}={value}" for key, value in alert.labels.items()]))
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def new_incident_id() -> str:
    """生成 inc-YYYYMMDD-NNN 形式的事故 id；NNN 是当天序号，从 incident 表当天最大值 +1.

    这个 id 同时是：网关 X-Incident-Id、落盘目录名、以及 T5.2 的 checkpointer thread_id。
    """
    today = datetime.now(UTC).strftime("%Y%m%d")
    seq = next_daily_seq(today)
    return f"inc-{today}-{seq:03d}"
