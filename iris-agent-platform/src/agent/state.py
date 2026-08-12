"""贯穿一次事故全流程的状态对象与预算台账."""

from __future__ import annotations

import operator
from enum import StrEnum
from typing import Annotated, Any, Literal, TypedDict

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
    """事故严重度，由 triage 判定.

    注意它和 Alertmanager labels.severity 不是一回事——AM 的是告警规则作者预设的
    静态标签，这个是模型看完整组告警后给的判断.
    """

    P0 = "P0"  # 核心链路不可用，用户大面积受影响
    P1 = "P1"  # 核心功能明显劣化（错误率/时延突破阈值），仍可部分服务
    P2 = "P2"  # 局部劣化或非核心链路，默认降级值也是它
    P3 = "P3"  # 观察级，暂不影响用户


class Domain(StrEnum):
    """疑似根因所属的技术域.

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


class VerdictEnum(StrEnum):
    """验收结论。只有三种，别再加中间态——回流判定靠它做分支."""

    PASS = "pass"
    FAIL = "fail"
    ABSENT = "absent"  # 本任务暂不使用，留给后续任务（跨家族 LLM 核验）


class Verdict(BaseModel):
    """一次验收的结论，由 verify 节点写入 IncidentState.verify_verdict."""

    verdict: VerdictEnum
    objections: list[str] = []
    checked_by: str | None = None  # 本任务暂不使用，留给后续任务


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
    evidence: Annotated[list[str], operator.add]            # 证据条目，追加型。M4 先记 {tool, args}，M7 起是 EV-* 编号
    verifier_feedback: Annotated[list[str], operator.add]   # verify 每次打回的意见，回流时注入 investigate 的 prompt（M4 写占位、M9 写真实异议）
    draft_report_ref: str | None  # 报告草稿的落盘路径，不是全文。M7 产出草稿、M8 落最终 report.md
    hypotheses_ref: str | None  # lead 编排产出的假设列表落盘路径（M10 T10.2）；M7 单 Agent 路径下恒为 None
    verify_verdict: Verdict | None

    # ---- 降级与预算 ----
    degraded: bool             # 本次链路上是否出现过网关降级；H3 一等信号，任何节点不许吞（M3 起就在写）
    degraded_reason: str | None  # 降级原因原文，来自网关信封同名字段
    budget: dict[str, Any]               # BudgetLedger 的 model_dump()，由 guard 读写（M4 写）

    # ---- 后续模块占位 ----
    approval: dict | None          # 人工审批决定。M12 的 approval 节点写入
    remediation_result: dict | None  # 修复执行结果。M12 的 remediate 节点写入

class RcaReport(BaseModel):
    """单 Agent（或 M10 的 lead）产出的根因分析结论。M7 产出，M8 只渲染不重定义."""

    root_service: str        # 根因服务名，必须是 CMDB 里的真实服务名；M12 判分的 acc@service 比对它
    root_cause: str          # 根因分类，用短语而非长句（如 "slow_query" / "threadpool_exhausted"）；判分的 acc@class 比对它
    root_detail: str         # 根因细节：具体是哪条 SQL / 哪个池 / 哪次配置变更
    confidence: Literal["high", "medium", "low"]  # 置信度；M8 在验收 fail 或预算耗尽时会强制降为 low
    causal_chain: list[str]  # 因果链，从根因到告警现象的有序步骤，每步一句话
    evidence_ids: list[str]  # 本结论引用的全部证据编号，必须都能在 evidence/ 里找到（M9 预检就查这个）
    summary_md: str          # 给人读的摘要 Markdown；**只有这个字段允许 M8 的 LLM 润色**
    remediation: str | None = None  # 修复建议；M12 的审批与执行以它为输入，慢查询类只准建议人工处理
