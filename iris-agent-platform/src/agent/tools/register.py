"""模板 / raw 工具的统一装配点：M7 的单 Agent 与 M10 的 subagent 都从这里取工具，预算记账统一包在 GatewayClient 上."""

from langchain_core.tools import BaseTool

from agent.guard import charge_tool_call, check_budget
from agent.state import BudgetLedger
from agent.tools.definetions import (
    make_query_cmdb,
    make_query_logs,
    make_query_logs_raw,
    make_query_metrics,
    make_query_metrics_raw,
    make_query_trace,
)
from agent.tools.GatewayClient import ApiEnvelope, GatewayClient


class _BudgetedGatewayClient(GatewayClient):
    """给 GatewayClient 包一层记账：调用前 check_budget，调用后按信封 charge_tool_call.

    继承而不是组合，是为了让实例本身仍然满足工具 factory 声明的 `GatewayClient`
    类型签名——4 个模板 / 2 个 raw factory 共用这一份记账逻辑，不在每个 factory 里各抄一遍.
    """

    ledger: BudgetLedger
    raw: bool = False

    def call(self, path: str, body: dict) -> ApiEnvelope:
        check_budget(self.ledger, raw=self.raw)
        envelope = super().call(path, body)
        charge_tool_call(self.ledger, envelope, raw=self.raw)
        return envelope


def _budgeted(
    client: GatewayClient, ledger: BudgetLedger, *, raw: bool
) -> GatewayClient:
    return _BudgetedGatewayClient(
        incident_id=client.incident_id,
        agent_role=client.agent_role,
        bearer_token=client.bearer_token,
        base_url=client.base_url,
        ledger=ledger,
        raw=raw,
    )


def make_template_tools(client: GatewayClient, ledger: BudgetLedger) -> list[BaseTool]:
    """4 个模板通道工具，走网关 per-incident 模板预算（上限 40）."""
    budgeted_client = _budgeted(client, ledger, raw=False)
    return [
        make_query_cmdb(budgeted_client),
        make_query_metrics(budgeted_client),
        make_query_logs(budgeted_client),
        make_query_trace(budgeted_client),
    ]


def make_raw_tools(client: GatewayClient, ledger: BudgetLedger) -> list[BaseTool]:
    """Return two raw-channel tools sharing an independent five-call budget."""
    budgeted_client = _budgeted(client, ledger, raw=True)
    return [
        make_query_metrics_raw(budgeted_client),
        make_query_logs_raw(budgeted_client),
    ]
