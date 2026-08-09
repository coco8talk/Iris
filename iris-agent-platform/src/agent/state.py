"""贯穿一次事故全流程的状态对象与预算台账."""

from __future__ import annotations

import operator
from enum import StrEnum
from typing import Annotated, Any, TypedDict

from pydantic import BaseModel, Field

from .config import get_settings


class BudgetLedger(BaseModel):
    """一次事故的预算台账：M4 的 guard 在每次 LLM/工具调用前后读写它."""

    tokens_used: int = 0        # 累计 LLM token（prompt+completion），由 guard.charge_tokens() 累加
    token_limit: int = Field(default_factory=lambda: get_settings().token_budget)
    # ↑ 单次事故的 token 上限，来自 Settings.token_budget，不硬编码在这里
    tool_calls_used: int = 0    # 累计网关"模板通道"调用次数
    tool_calls_limit: int = Field(default_factory=lambda: get_settings().tool_call_budget)
    # ↑ 模板通道上限，与 M2 网关侧 per-incident 预算（模板 30）保持一致
    raw_calls_used: int = 0     # 累计 raw 通道调用次数；raw 工具 M7 才接入，M4 恒为 0
    raw_calls_limit: int = Field(default_factory=lambda: get_settings().raw_call_budget)
    # ↑ raw 通道上限，与网关侧（raw 5）保持一致，与模板通道分开计
    deadline_ts: float | None = None  # 墙钟截止时间（epoch 秒）；None 表示还没初始化，由 guard.load_ledger() 补上
    budget_exhausted: bool = False    # 任一维度触顶即置 True；report 节点读它，在报告里标注"因预算耗尽提前收口"

class Severity(StrEnum):
    """事故严重度，由 triage 判定；注意它和 Alertmanager labels.severity 不是一回事——
    AM 的是告警规则作者预设的静态标签，这个是模型看完整组告警后给的判断."""

    P0 = "P0"  # 核心链路不可用，用户大面积受影响
    P1 = "P1"  # 核心功能明显劣化（错误率/时延突破阈值），仍可部分服务
    P2 = "P2"  # 局部劣化或非核心链路，默认降级值也是它
    P3 = "P3"  # 观察级，暂不影响用户


class Domain(StrEnum):
    """疑似根因所属的技术域。

    这 7 个字面值是与 M11 Skills 预筛映射表的硬契约，改任何一个都要同步改 M11 的
    loader.listing_for()，否则预筛会静默返回空列表。
    """

    DB = "db"                  # 数据库层：慢查询、锁等待、连接池打满
    THREADPOOL = "threadpool"  # 线程池/下游超时：池耗尽、队列堆积
    JVM = "jvm"                # JVM 层：GC 压力、堆内存、类加载
    RESOURCE = "resource"      # 宿主资源：CPU/磁盘/网络饱和
    CONTAINER = "container"    # 容器/实例：宕掉、CrashLoop、副本数不足
    CONFIG = "config"          # 配置变更：Nacos 发布、开关误改
    CACHE = "cache"            # 缓存：Redis 不可用、击穿/雪崩


class TriageResult(BaseModel):
    """轻模型对一组告警做的初步分诊结果，由 M6 的 triage 节点写入 IncidentState.triage."""

    severity: Severity                # 事故严重度，降级默认值为 P2
    suspected_domains: list[Domain]   # 疑似技术域，可多选；降级默认值为全部 7 个（宁滥勿缺）
    # fast_path / candidate_root 两个字段在 M11 补——它们依赖 matched_cases，
    # 而 matched_cases 要等 M11 的 Chroma 检索才有真实来源。



class IncidentState(TypedDict, total=False):
    """一次事故从告警进入到报告出口的全流程共享状态.

    硬约定：大文本一律落盘、state 只存 `*_ref` 路径或 id [R13]。
    `total=False` 表示所有字段都可缺省——谁写谁给，读的时候一律用 state.get()。
    """

    # ---- 入口输入 ----
    incident_id: str    # 事故唯一 id；贯穿网关 X-Incident-Id、证据目录名、日志关联。M4 由 run input 给，M5 起由 webhook 生成
    service: str        # 告警直接指向的服务名，是 investigate 的起点（注意：不等于根因服务）。同上
    fingerprint: str    # 告警指纹，30min 去重用。M5 的 webhook 写入（优先取 Alertmanager 自带 fingerprint），M4 恒缺省
    alerts_ref: str     # 原始告警 payload 的落盘路径（不是 payload 本身）。M5 写入，M4 恒缺省

    # ---- 各节点产出 ----
    matched_cases: list[dict]        # 历史相似案例命中列表。M11 的 knowledge_match 节点写入，元素类型届时为 KnowledgeHit
    triage: TriageResult | None      # 分诊结果。M6 的 triage 节点写入，届时类型收紧为 TriageResult（在 M6 定义）
    investigate_rounds: int          # investigate 已跑轮数；条件边靠它判断还能不能回流。由 investigate 节点自己 +1（M4 写）
    metrics_result: dict[str, Any] | None
    evidence: Annotated[list[dict], operator.add]           # 证据条目，追加型。M4 先记 {tool, args}，M7 换成 EV-* 台账条目
    verifier_feedback: Annotated[list[str], operator.add]   # verify 每次打回的意见，回流时注入 investigate 的 prompt（M4 写占位、M9 写真实异议）
    draft_report_ref: str | None  # 报告草稿的落盘路径，不是全文。M7 产出草稿、M8 落最终 report.md
    verify_verdict: dict | None   # 验收结论。M4 先由确定性预检写，M9 补跨家族 LLM 核验，届时类型收紧为 Verdict（在 M9 定义）

    # ---- 降级与预算 ----
    degraded: bool             # 本次链路上是否出现过网关降级；H3 一等信号，任何节点不许吞（M3 起就在写）
    degraded_reason: str | None  # 降级原因原文，来自网关信封同名字段
    budget: dict[str, Any]               # BudgetLedger 的 model_dump()，由 guard 读写（M4 写）

    # ---- 后续模块占位 ----
    approval: dict | None          # 人工审批决定。M12 的 approval 节点写入
    remediation_result: dict | None  # 修复执行结果。M12 的 remediate 节点写入
