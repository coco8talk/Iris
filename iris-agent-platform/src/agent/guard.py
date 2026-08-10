"""预算与墙钟 guard：在每次 LLM/工具调用处累计并检查，超限即抛，由节点收口."""
import time

import structlog
from langchain_core.messages import AIMessage

from agent.config import get_settings
from agent.state import BudgetLedger, IncidentState
from agent.tools.GatewayClient import ApiEnvelope


class BudgetExhaustedError(Exception):
    """本次事故的 token / 模板通道 / raw 通道 / 墙钟，任一维度触顶."""


def load_ledger(state: IncidentState) -> BudgetLedger:
    """从 state["budget"] 还原台账；首次调用时按 Settings 初始化各上限.

    并把 deadline_ts 补成 now + settings.wall_clock_budget_seconds.
    """
    budget = state.get("budget")
    if budget is not  None:
        return BudgetLedger.model_validate(budget)

    budget = get_settings()
    return BudgetLedger(
        token_limit= budget.token_budget,
        tool_calls_limit=budget.tool_call_budget,
        raw_calls_limit=budget.raw_call_budget,
        deadline_ts= time.time() + budget.wall_clock_budget_seconds,
    )

def check_budget(ledger: BudgetLedger, *, raw: bool = False) -> None:
    """在每次 LLM 调用前、每次网关工具调用前各调一次.

    token 与墙钟预算对两个通道都生效；模板调用只检查 tool_calls_limit，raw 调用只检查
    raw_calls_limit，避免任一通道耗尽后误伤另一个通道。
    """
    channel_exhausted = (
        ledger.raw_calls_used >= ledger.raw_calls_limit
        if raw
        else ledger.tool_calls_used >= ledger.tool_calls_limit
    )
    if (ledger.tokens_used>=ledger.token_limit
            or channel_exhausted
            or (ledger.deadline_ts is not None and time.time()>=ledger.deadline_ts)):
        raise BudgetExhaustedError


def charge_tokens(ledger: BudgetLedger, response: AIMessage) -> BudgetLedger:
    """从 response.usage_metadata["total_tokens"] 累加 tokens_used，返回更新后的台账.

    模型没回 usage_metadata 时按 0 记并留一条日志，不要静默当成没花钱.
    """
    usage = response.usage_metadata
    total_token = usage["total_tokens"] if usage else 0
    ledger.tokens_used += total_token

    logger = structlog.getLogger()
    if not usage:
        logger.warning("charge_tokens_no_usage_metadata", tokens_used=ledger.tokens_used)
    else:
        logger.info(
            "charge_tokens",
            total_token=total_token,
            tokens_used=ledger.tokens_used,
        )
    return ledger


def charge_tool_call(
    ledger: BudgetLedger,
    envelope: ApiEnvelope,
    *,
    raw: bool = False,
) -> BudgetLedger:
    """累加 tool_calls_used（raw=True 时累加 raw_calls_used），并用信封校准本地计数.

    两个通道复用信封里同一个 meta.tool_calls_remaining（网关按当前通道返回该通道剩余次数），
    写进台账的哪个字段由调用方传的 raw 决定。已用次数 = 本地上限 - 网关剩余次数；
    本地与网关不一致时以网关为准，因为网关那边才是真正会返回 429 的人。
    meta 缺省（如参数校验失败）时退化成本地 +1，不能当成没调用过。
    """
    if raw:
        ledger.raw_calls_used += 1
        if envelope.meta is not None:
            ledger.raw_calls_used = ledger.raw_calls_limit - envelope.meta.tool_calls_remaining
    else:
        ledger.tool_calls_used += 1
        if envelope.meta is not None:
            ledger.tool_calls_used = ledger.tool_calls_limit - envelope.meta.tool_calls_remaining

    logger = structlog.getLogger()
    logger.info(
        "charge_tool_call",
        raw=raw,
        tool_calls_used=ledger.tool_calls_used,
        raw_calls_used=ledger.raw_calls_used,
    )
    return ledger
