import time

import pytest

from agent.guard import BudgetExhaustedError, check_budget
from agent.state import BudgetLedger


def make_ledger(**updates: int | float) -> BudgetLedger:
    values = {
        "token_limit": 1000,
        "tool_calls_limit": 40,
        "raw_calls_limit": 5,
        "deadline_ts": time.time() + 60,
    }
    values.update(updates)
    return BudgetLedger(**values)


def test_raw_budget_exhaustion_does_not_block_template_channel() -> None:
    ledger = make_ledger(raw_calls_used=5, tool_calls_used=0)

    with pytest.raises(BudgetExhaustedError):
        check_budget(ledger, raw=True)

    check_budget(ledger, raw=False)


def test_template_budget_exhaustion_does_not_block_raw_channel() -> None:
    ledger = make_ledger(tool_calls_used=40, raw_calls_used=0)

    with pytest.raises(BudgetExhaustedError):
        check_budget(ledger, raw=False)

    check_budget(ledger, raw=True)


@pytest.mark.parametrize(
    "updates",
    [
        {"tokens_used": 1000},
        {"deadline_ts": time.time() - 1},
    ],
)
def test_common_budgets_block_both_channels(updates: dict[str, int | float]) -> None:
    ledger = make_ledger(**updates)

    with pytest.raises(BudgetExhaustedError):
        check_budget(ledger, raw=False)
    with pytest.raises(BudgetExhaustedError):
        check_budget(ledger, raw=True)
