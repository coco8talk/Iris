from sre_copilot.graph.state import RcaReport


# 兜底产出通道

def render(report: RcaReport) -> str:
    causal_chain = (
            "\n".join(
                f"{i}.{step}"
                for i, step in enumerate(report.causal_chain, 1)
            )
            or "(无)"
    )
    evidence_list = (
            "\n".join(
                f"- [{fid}]"
                for fid in report.evidence_ids
            )
            or "- (本报告没有任何有效证据引用)"
    )

    if report.remediation is None:
        remediation = "(无建议，需人工判断)"
    else:
        params = "".join(
            f"\n - {key}: {value}"
            for key, value in report.remediation.params.items()
        )
        remediation = (
            f"- 动作：`{report.remediation.action}`\n"
            f"- 对象：`{report.remediation.target}{params}`"
        )

    degraded = (
        f"**本报告为降级产出**:{report.degraded_reason or '原因未记录'}"
        if report.degraded
        else "无降级，取证与生成过程正常。"
    )

    return (
        "# RCA 报告\n\n"
        "## 结论\n\n"
        f"- 根因服务: **{report.root_service}**\n"
        f"- 根因类别: **{report.root_cause}**\n"
        f"- 置信度: **{report.confidence}**\n\n"
        "## 因果链\n\n"
        f"{causal_chain}\n\n"
        "## 摘要\n\n"
        f"{report.summary_md}\n\n"
        "## 证据引用清单\n\n"
        f"{evidence_list}\n\n"
        "## 修复建议\n\n"
        f"{remediation}\n\n"
        "## 降级说明\n\n"
        f"{degraded}\n"
    )
