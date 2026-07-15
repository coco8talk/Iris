import json
from typing import Any, Literal

from langchain_core.tools import BaseTool, StructuredTool
from pydantic import BaseModel, Field

from sre_copilot.tools.client import GatewayClient

_QUERY_CMDB_DESCRIPTION = (
    "查询 CMDB 静态事实（只读）。"
    "template=get_topology：返回服务依赖拓扑（gateway→order→{inventory,payment}）"
    "与各服务的中间件依赖，无需 service 参数；"
    "template=get_service_detail：返回单个服务的容器名、端口、JVM 参数要点、"
    "关键配置项与负责人，必须提供 service 参数。"
    "响应中 degraded=true 表示数据源降级、data 可能为空或不完整，应考虑改用其他工具；"
    "budget_remaining 为本次诊断剩余的工具调用预算，为负数时表示预算未启用。"
)


class QueryCmdbArgs(BaseModel):
    template: Literal["get_topology", "get_service_detail"] = Field(
        description=(
            "查询模板：get_topology（全局依赖拓扑，无需 service）"
            "或 get_service_detail（单服务详情，必须提供 service）"
        ),
    )
    service: str | None = Field(
        default=None,
        description="服务名，仅 get_service_detail 需要；必须是 CMDB 已知服务，如 order-service",
    )


def make_tools(client: GatewayClient) -> dict[str, BaseTool]:
    def _query_cmdb(
            template: Literal["get_topology", "get_service_detail"],
            service: str | None = None,
    ) -> str:
        body: dict[str, Any] = {
            "template": template,
        }
        if service:
            body["service_name"] = service

        envelope = client.call("/tools/query_cmdb", body)
        return json.dumps(
            {
                "degraded": envelope.degraded,
                "degraded_reason": envelope.degraded_reason,
                "budget_remaining": envelope.meta.budget_remaining,
                "data": envelope.data,
            },
            ensure_ascii=False,
        )

    query_cmdb = StructuredTool.from_function(
        func=_query_cmdb,
        name="query_cmdb",
        description=_QUERY_CMDB_DESCRIPTION,
        args_schema=QueryCmdbArgs,
    )
    return {"query_cmdb": query_cmdb}
