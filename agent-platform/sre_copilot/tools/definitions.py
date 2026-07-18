import json
from typing import Any, Literal

from httpx import HTTPStatusError
from langchain_core.tools import BaseTool, StructuredTool
from pydantic import BaseModel, Field

from sre_copilot.tools.client import (
    BudgetExceededError,
    GatewayClient,
    GatewayRequestError,
)

# 查询时间窗口的统一类型，网关护栏规定模板通道窗口
Window = Literal["15m", "30m", "1h", "6h"]

# 所有工具描述共用的一个尾注：教模型正确理解 degraded 信号
_DEGRADED_NOTE = (
    "响应中 degraded=true 表示数据降级或查询失败、data 可能为空，"
    "此时信息不完整，不得据此编造结论；budget_remaining 为剩余工具调用预算"
)

_QUERY_METRICS_DESCRIPTION = (
        "查询服务指标的统计摘要与异常提示（只读）。适合验证/容错率/资源类假设。"
        "compare_baseline=true 时对比 24h 前同窗口基线。" + _DEGRADED_NOTE
)

_QUERY_LOGS_DESCRIPTION = (
        "查询服务日志,返回模板化聚合 patterns(按出现次数降序,含 sample_trace_ids)"
        "与原始行 lines。适合定位错误类型与提取 trace_id。" + _DEGRADED_NOTE
)

_QUERY_TRACE_DESCRIPTION = (
        "查询分布式链路(只读)。find_slow_traces/find_error_traces 返回慢/错 trace 摘要"
        "(top_spans 按耗时占比排序);get_trace 需 trace_id,返回精简 span 树。"
        "适合定位跨服务瓶颈。" + _DEGRADED_NOTE
)

_QUERY_CMDB_DESCRIPTION = (
        "查询 CMDB 静态事实(只读)。"
        "template=get_topology:返回服务依赖拓扑(gateway→order→{inventory,payment})"
        "与各服务的中间件依赖,无需 service 参数;"
        "template=get_service_detail:返回单个服务的容器名、端口、JVM 参数要点、"
        "关键配置项与负责人,必须提供 service 参数。" + _DEGRADED_NOTE
)

_QUERY_CHANGES_DESCRIPTION = (
        "查询窗口内的变更事件(发版/配置变更/重启,只读),返回 changes 列表"
        "(change_id/ts/type/service/summary/operator)。诊断早期应先查最近变更。"
        + _DEGRADED_NOTE
)


class QueryMetricsArgs(BaseModel):
    template: Literal[
        "qps", "error_rate", "p99_latency", "jvm_heap_usage", "jvm_gc_pause",
        "threadpool_active", "threadpool_queue", "db_pool_usage",
        "db_query_time", "cpu_usage", "disk_free", "cache_error_rate",
    ] = Field(description="指标模板")
    service: str = Field(description="CMDB 已知服务名,如 order-service")
    window: Window = Field(default="30m", description="查询窗口,最大 6h")
    compare_baseline: bool = Field(
        default=False, description="是否对比 24h 前同窗口基线"
    )


class QueryLogsArgs(BaseModel):
    service: str = Field(description="CMDB 已知服务名")
    level: Literal["ERROR", "WARN"] | None = Field(
        default=None, description="日志级别过滤,可空"
    )
    keyword: str | None = Field(default=None, description="关键字行过滤,可空")
    window: Window = Field(default="30m", description="查询窗口,最大 6h")
    limit: int = Field(default=50, ge=1, le=100, description="原始行数上限,≤100")


class QueryTraceArgs(BaseModel):
    template: Literal["find_slow_traces", "find_error_traces", "get_trace"] = Field(
        description="find_slow_traces 需 service+min_duration_ms;"
                    "find_error_traces 需 service;get_trace 需 trace_id"
    )
    service: str | None = Field(default=None, description="服务名,find_* 模板必填")
    min_duration_ms: int | None = Field(
        default=None, description="慢 trace 阈值毫秒,仅 find_slow_traces"
    )
    window: Window = Field(default="30m", description="查询窗口,最大 6h")
    limit: int = Field(default=5, ge=1, le=10, description="返回条数上限,≤10")
    trace_id: str | None = Field(default=None, description="仅 get_trace 必填")


class QueryCmdbArgs(BaseModel):
    template: Literal["get_topology", "get_service_detail"] = Field(
        description=(
            "查询模板:get_topology(全局依赖拓扑,无需 service)"
            "或 get_service_detail(单服务详情,必须提供 service)"
        ),
    )
    service: str | None = Field(
        default=None,
        description="服务名,仅 get_service_detail 需要;必须是 CMDB 已知服务,如 order-service",
    )


class QueryChangesArgs(BaseModel):
    window: Window = Field(default="6h", description="回溯窗口,最大 6h")
    service: str | None = Field(default=None, description="按服务过滤,可空")


def make_tools(client: GatewayClient) -> dict[str, BaseTool]:
    def _call(path: str, body: dict[str, Any]) -> str:
        try:
            envelope = client.call(path, body)
        except BudgetExceededError:
            raise
        except (GatewayRequestError, HTTPStatusError) as exc:
            return json.dumps(
                {
                    "degraded": True,
                    "degraded_reason": f"gateway call failed: {exc}",
                    "budget_remaining": -1,
                    "data": None,
                },
                ensure_ascii=False,
            )
        return json.dumps(
            {
                "degraded": envelope.degraded,
                "degraded_reason": envelope.degraded_reason,
                "budget_remaining": envelope.meta.budget_remaining,
                "data": envelope.data,
            },
            ensure_ascii=False,
        )

    def _query_metrics(
            template: str,
            service: str,
            window: str = "30m",
            compare_baseline: bool = False
    ) -> str:
        return _call(
            "/tools/query_metrics",
            {
                "template": template,
                "service": service,
                "window": window,
                "compare_baseline": compare_baseline,
            },
        )

    def _query_logs(
            service: str,
            level: str | None = None,
            keyword: str | None = None,
            window: str = "30m",
            limit: int = 50,
    ) -> str:
        body: dict[str, Any] = {
            "service": service,
            "window": window,
            "limit": limit,
        }

        if level:
            body["level"] = level
        if keyword:
            body["keyword"] = keyword

        return _call(
            "/tools/query_logs",
            body,
        )

    def _query_trace(
            template: str,
            service: str | None = None,
            min_duration_ms: int | None = None,
            window: str = "30m",
            limit: int = 5,
            trace_id: str | None = None,
    ) -> str:
        body: dict[str, Any] = {"template": template, "window": window, "limit": limit}
        if service:
            body["service"] = service
        # 0 也是合法阈值,所以用 is not None 而不是真值判断
        if min_duration_ms is not None:
            body["min_duration_ms"] = min_duration_ms
        if trace_id:
            body["trace_id"] = trace_id
        return _call("/tools/query_trace", body)

    def _query_cmdb(
            template: Literal["get_topology", "get_service_detail"],
            service: str | None = None,
    ) -> str:
        body: dict[str, Any] = {"template": template}
        if service:
            # 网关 DTO 字段是 serviceName,SNAKE_CASE 策略下对应 service_name
            body["service_name"] = service
        return _call("/tools/query_cmdb", body)

    def _query_changes(window: str = "6h", service: str | None = None) -> str:
        body: dict[str, Any] = {"window": window}
        if service:
            body["service"] = service
        return _call("/tools/query_changes", body)

    specs = [
        ("query_metrics", _query_metrics, _QUERY_METRICS_DESCRIPTION, QueryMetricsArgs),
        ("query_logs", _query_logs, _QUERY_LOGS_DESCRIPTION, QueryLogsArgs),
        ("query_trace", _query_trace, _QUERY_TRACE_DESCRIPTION, QueryTraceArgs),
        ("query_cmdb", _query_cmdb, _QUERY_CMDB_DESCRIPTION, QueryCmdbArgs),
        ("query_changes", _query_changes, _QUERY_CHANGES_DESCRIPTION, QueryChangesArgs),
    ]

    return {
        name: StructuredTool.from_function(
            name=name, func=func, description=description, args=args
        )
        for name, func, description, args in specs
    }
