import json
from collections.abc import Mapping
from typing import Any

from langchain_core.language_models import BaseChatModel
from langchain_core.tools import BaseTool

from sre_copilot.agents.evidence import (
    EVIDENCE_ID_RE,
    EvidenceStore,
    make_record_evidence_tool,
)
from sre_copilot.agents.simple_agent import build_simple_agent
from sre_copilot.graph.nodes.report_template import render
from sre_copilot.graph.state import RcaReport, RemediationSuggestion

# 每个 ToolCallLimitMiddleware 都会增加图节点步数；真实工具调用由下方预算单独限制。
_RECURSION_LIMIT = 150

_SYSTEM_PROMPT = """你是 SRE 根因诊断 Agent,面对一条真实告警,用只读工具取证并产出 RCA 报告。

排查纪律:
1. 根据告警、已有证据和工具描述,自主选择能够验证当前假设的工具,必要时进行多轮取证。
2. 只调用推进诊断所需的工具,不要为了凑流程调用无关工具;结论必须来自工具返回的事实。

终止纪律:
1. 同一查询失败、返回 degraded=true 或 data 为空后,不得更换无关参数重复试探。
2. 获得足够证据后立即输出报告;关键数据源不可用时,直接输出低置信降级报告并说明缺失信息。
3. 整次诊断最多进行 12 次查询工具调用,达到上限时必须停止调用工具并输出当前最佳报告。

证据纪律:
1. 每次工具查询得到支撑或排除假设的关键结果后,必须立即调用 record_evidence 登记,获得 EV-* 编号。
2. 报告的 evidence_ids 与正文中的 [EV-*] 引用,只允许使用 record_evidence 实际返回的编号,禁止编造。
3. 结论中每个关键论断句都要附 [EV-*] 引用;没有证据支撑的推测必须明确标注为推测。

降级纪律:
工具返回 degraded=true 或调用失败时,如实说明信息不完整,不得编造数据;
关键证据缺失时置信度不得为 high,并在 summary_md 中说明缺了什么。

修复建议纪律:
kill_slow_query 只是临时缓解,绝不能作为根因修复建议;
慢 SQL / 缺索引类根因的 remediation 必须是 action=manual_review(目标:人工审核后补建索引)。

最终以 RcaReport 结构化输出,summary_md 用中文。"""


def investigate(
        alert: dict[str, Any],
        model: BaseChatModel,
        tools: Mapping[str, BaseTool],
        evidence_store: EvidenceStore,
) -> RcaReport:
    """单 Agent 诊断入口:取证 → 结构化报告 → 确定性核验;任何失败都兜底出低置信报告。"""
    evidence_store.write_alert(alert)
    all_tools = [*tools.values(), make_record_evidence_tool(evidence_store)]
    try:
        agent = build_simple_agent(
            model=model,
            tools=all_tools,
            system_prompt=_SYSTEM_PROMPT,
            response_schema=RcaReport,
            name="investigator",
            tool_call_limits={
                "query_cmdb": 2,
                "query_changes": 2,
                "query_metrics": 3,
                "query_logs": 2,
                "query_trace": 2,
                "record_evidence": 8,
            },
            model_call_limit=12,
        )
        result = agent.invoke(
            {"messages": [{"role": "user", "content": _alert_prompt(alert)}]},
            config={"recursion_limit": _RECURSION_LIMIT},
        )
        raw = result.get("structured_response")
        if not isinstance(raw, RcaReport):
            raw = _complete_report(model, alert, evidence_store)
        report = finalize_report(raw, evidence_store)
    except Exception as exc:  # 失败必须有产出且不伪装成功(确定性 fallback)
        report = fallback_report(alert, evidence_store, f"{type(exc).__name__}: {exc}")
    evidence_store.write_report_draft(render(report))
    return report


def finalize_report(raw: RcaReport, store: EvidenceStore) -> RcaReport:
    """确定性核验:引用必须真实存在;kill_slow_query 不得作为根因修复。"""
    cited: set[str] = set(raw.evidence_ids)
    for text in (raw.summary_md, *raw.causal_chain):
        cited.update(EVIDENCE_ID_RE.findall(text))
    valid = sorted(eid for eid in cited if store.exists(eid))
    fake = sorted(cited - set(valid))

    updates: dict[str, Any] = {"evidence_ids": valid}
    problems: list[str] = []
    if fake:
        problems.append(f"引用了不存在的证据编号并已剔除: {', '.join(fake)}")
    if not valid:
        problems.append("结论没有任何有效证据支撑")
    if problems:
        reason = ";".join(problems)
        updates.update(
            confidence="low",
            degraded=True,
            degraded_reason=(
                f"{raw.degraded_reason};{reason}" if raw.degraded_reason else reason
            ),
        )
    if raw.remediation is not None and raw.remediation.action == "kill_slow_query":
        updates["remediation"] = RemediationSuggestion(
            action="manual_review",
            target=raw.remediation.target,
            params={
                **raw.remediation.params,
                "note": "kill_slow_query 仅为临时缓解,不构成根因修复;请人工审核后补建索引",
            },
        )
    return raw.model_copy(update=updates)


def fallback_report(
        alert: dict[str, Any], store: EvidenceStore, reason: str
) -> RcaReport:
    evidence_ids = store.list_ids()
    return RcaReport(
        root_service="none",
        root_cause="no_fault_found",
        confidence="low",
        causal_chain=["诊断过程中断,未能建立完整因果链"],
        summary_md=(
            "诊断未正常完成,本报告由确定性模板生成。\n\n"
            f"- 中断原因: {reason}\n"
            f"- 中断前已登记证据 {len(evidence_ids)} 条,见证据引用清单\n"
            "- 未得出根因结论,需要人工接管排查"
        ),
        evidence_ids=evidence_ids,
        remediation=RemediationSuggestion(
            action="manual_review",
            target=str(alert.get("labels", {}).get("service", "unknown")),
        ),
        degraded=True,
        degraded_reason=reason,
    )


def _alert_prompt(alert: dict[str, Any]) -> str:
    return (
            "收到以下告警,请开始根因诊断:\n```json\n"
            + json.dumps(alert, ensure_ascii=False, indent=2)
            + "\n```"
    )


def _complete_report(
        model: BaseChatModel,
        alert: dict[str, Any],
        store: EvidenceStore,
) -> RcaReport:
    """取证 Agent 达到预算时，用已登记证据让真实模型完成结构化报告。"""
    evidence = "\n\n".join(store.read(eid) for eid in store.list_ids())

    reporter = build_simple_agent(
        model=model,
        tools=[],
        system_prompt=(
            _SYSTEM_PROMPT
            + "\n你现在处于报告收敛阶段,不能再调用查询工具。"
            "请基于已登记证据立即输出 RcaReport;证据不足时必须输出低置信降级报告。"
        ),
        response_schema=RcaReport,
        name="investigator-reporter",
    )

    result = reporter.invoke(
        {
            "messages": [{
                "role": "user",
                "content": (
                    _alert_prompt(alert)
                    + "\n\n以下是本次诊断已登记的全部证据:\n\n"
                    + (evidence or "（无已登记证据）")
                ),
            }]
        },
        config={"recursion_limit": 10},
    )
    report = result.get("structured_response")
    if not isinstance(report, RcaReport):
        raise TypeError("reporter did not return a structured RcaReport")
    return report
