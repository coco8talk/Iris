from typing import Literal

from pydantic import BaseModel, Field

# 最小版本;T31 落地全量 IncidentState 时以执行计划 §T31 为准收敛字段。
RemediationAction = Literal[
    "restart_container",
    "kill_slow_query",
    "revert_config",
    "disable_chaos_flag",
    "manual_review",
]


class RemediationSuggestion(BaseModel):
    action: RemediationAction = Field(
        description=(
            "修复动作。kill_slow_query 只是临时缓解,不允许作为根因修复;"
            "慢 SQL/缺索引类根因必须用 manual_review(人工审核后补索引)。"
        )
    )
    target: str = Field(description="动作对象,如服务名、慢查询摘要、变更 change_id")
    params: dict[str, str] = Field(
        default_factory=dict,
        description=(
            "执行该动作所需的补充参数,键值均为字符串;无补充信息时留空。"
            '例:manual_review 建议附 {"reason": "疑似缺索引", "suggestion": "审核后为 t_order.status 补索引"};'
            'revert_config 附 {"change_id": "chg-0042"}。'
        ),
    )


class RcaReport(BaseModel):
    root_service: str = Field(description="根因服务名;无法定位时为 none")
    root_cause: str = Field(description="根因类别短语;无法定位时为 no_fault_found")
    confidence: Literal["high", "medium", "low"]
    causal_chain: list[str] = Field(
        description="从根因到告警症状的因果链,每项一句话,关键论断附 [EV-*] 引用"
    )
    summary_md: str = Field(
        description="Markdown 结论摘要,每个结论句必须引用 [EV-*] 证据编号"
    )
    evidence_ids: list[str] = Field(
        description="报告引用的全部证据编号,只允许使用 record_evidence 返回的编号"
    )
    remediation: RemediationSuggestion | None = None
    degraded: bool = Field(
        default=False, description="取证或生成过程发生降级/失败时为 true"
    )
    degraded_reason: str | None = None
