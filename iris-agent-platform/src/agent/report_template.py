from agent.state import RcaReport


def render(report: RcaReport, verdict: dict, budget: dict, rounds_exhausted: bool) -> str:
    confidence = report.confidence
    notes = []  # 三种标注攒在这里

    if verdict.get("verdict") == "fail" and rounds_exhausted:
        notes.append("验收未通过，结论仅供参考")
        confidence = "low"
    if verdict.get("verifier_absent"):
        notes.append("验收缺席，未经跨家族核验")
    if budget.get("budget_exhausted"):
        notes.append("预算耗尽，基于现有证据出具，可能存在未排查的方向")

    return f"""## 结论
{report.root_service} / {report.root_cause} / {report.root_detail}

## 置信度
{confidence}
{chr(10).join(notes)}

## 因果链
{chr(10).join(f"{i + 1}. {step}" for i, step in enumerate(report.causal_chain))}

## 证据引用清单
{chr(10).join(f"- [{eid}](evidence/{eid}.md)" for eid in report.evidence_ids)}

## 修复建议
{report.remediation or "无自动修复建议，需人工判断"}

## 验收异议
{chr(10).join(verdict.get("objections", [])) or "无"}
"""
