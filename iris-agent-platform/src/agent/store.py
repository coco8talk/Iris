"""事故的 SQLite 持久化：incident 表 + 当天序号计数器.

只负责数据库读写；alert.json 的落盘/追加是 paths.py 的职责，这里不碰文件系统。
"""

import time
from pathlib import Path
from typing import cast

import structlog
from sqlalchemy import update
from sqlalchemy.dialects.sqlite import insert as sqlite_upsert
from sqlmodel import Field, Session, SQLModel, col, create_engine, select

logger = structlog.getLogger()

DB_PATH = Path("runs/incidents.db")
DB_PATH.parent.mkdir(parents=True, exist_ok=True)

engine = create_engine(
    f"sqlite:///{DB_PATH}",
    connect_args={"check_same_thread": False},  # 后台任务/请求处理不在同一线程时也能用
)


class Incident(SQLModel, table=True):
    """Persisted incident metadata and lifecycle status."""

    __tablename__ = "incident"

    incident_id: str = Field(primary_key=True)  # inc-YYYYMMDD-NNN
    fingerprint: str = Field(nullable=False, index=True)  # 去重键
    status: str = Field(nullable=False)  # open | running | closed | failed

    created_at: float = Field(nullable=False)  # epoch 秒
    updated_at: float = Field(nullable=False)  # epoch 秒，去重窗口以它为准

    alerts_ref: str | None = None  # alert.json 的路径，不存 payload 全文
    last_error: str | None = None  # 后台图执行异常落这里


class DailySeq(SQLModel, table=True):
    """Per-day counter used to allocate incident sequence numbers."""

    __tablename__ = "daily_seq"

    date: str = Field(primary_key=True)  # YYYYMMDD
    seq: int = Field(nullable=False)


def create_db_and_tables() -> None:
    """Create all configured SQLModel tables when they do not exist."""
    SQLModel.metadata.create_all(engine)


def next_daily_seq(date: str) -> int:
    """原子返回 date 当天的下一个序号（从 1 开始），单条 UPSERT 完成，不存在读-算-写竞态."""
    stmt = (
        sqlite_upsert(DailySeq)
        .values(date=date, seq=1)
        .on_conflict_do_update(
            index_elements=[DailySeq.date],
            set_={"seq": col(DailySeq.seq) + 1},
        )
        .returning(col(DailySeq.seq))
    )
    with Session(engine) as session:
        seq = session.exec(stmt).scalar_one()
        session.commit()
        return seq


def find_active(fingerprint: str, within_seconds: int = 1800) -> str | None:
    """查 within_seconds 内同指纹且 status in ('open','running') 的 incident_id，没有则 None."""
    cutoff = time.time() - within_seconds
    statement = (
        select(Incident)
        .where(col(Incident.fingerprint) == fingerprint)
        .where(col(Incident.status).in_(("open", "running")))
        .where(col(Incident.updated_at) >= cutoff)
        .order_by(col(Incident.updated_at).desc())
        .limit(1)
    )
    with Session(engine) as session:
        incident = session.exec(statement).first()
        if incident is None:
            return None
        return incident.incident_id


def create_incident(incident_id: str, fingerprint: str, alerts_ref: str) -> None:
    """Create an open incident with the supplied identifier and alert reference."""
    now = time.time()
    incident = Incident(
        incident_id=incident_id,
        fingerprint=fingerprint,
        status="open",
        created_at=now,
        updated_at=now,
        alerts_ref=alerts_ref,
    )
    with Session(engine) as session:
        session.add(incident)
        session.commit()


def merge_alert(incident_id: str) -> None:
    """命中去重时的落库部分：只推进 updated_at，扩大 30 分钟去重窗口.

    alerts_ref 不在这里改——它一直指向同一个 alert.json；把新告警追加进该文件的
    alerts[] 是调用方经 paths.py 完成的文件操作，不属于这一层，所以这里不需要接收 Alert 本身。
    """
    with Session(engine) as session:
        incident = session.get(Incident, incident_id)
        if incident is None:
            # 调用方刚从 find_active 拿到这个 id 才会走到这里，查不到说明
            # 记录在两次查询之间被删了或者 id 传错了——静默 return 会让这条
            # 告警凭空消失，必须留痕。
            logger.warning("merge_alert_incident_missing", incident_id=incident_id)
            return
        incident.updated_at = time.time()
        session.add(incident)
        session.commit()


def mark_status(incident_id: str, status: str, last_error: str | None = None) -> None:
    """推进状态；一旦 closed/failed 就不再被覆盖，防止正常完成和优雅退出的 cancel 互相踩."""
    values: dict = {"status": status, "updated_at": time.time()}
    if last_error is not None:
        values["last_error"] = last_error

    stmt = (
        update(Incident)
        .values(**values)
        .where(col(Incident.incident_id) == incident_id)
        .where(col(Incident.status).not_in(("closed", "failed")))
    )
    with Session(engine) as session:
        result = session.exec(stmt)
        session.commit()

    # 终态守卫把 UPDATE 拦成 0 行是正常业务分支（比如图正常跑完标了 closed 之后，
    # 优雅退出又想标 failed），但排障时“我明明调了 mark_status 状态却没变”极易
    # 被误判成 bug，所以两种结果都记一行，区别只在级别。
    if result.rowcount == 0:
        logger.debug(
            "mark_status_noop",
            incident_id=incident_id,
            status=status,
            reason="already_terminal",
        )
    else:
        logger.info("mark_status", incident_id=incident_id, status=status)


def get_incident(incident_id: str) -> Incident | None:
    """Return an incident by identifier, or ``None`` when it does not exist."""
    with Session(engine) as session:
        return cast(Incident | None, session.get(Incident, incident_id))
