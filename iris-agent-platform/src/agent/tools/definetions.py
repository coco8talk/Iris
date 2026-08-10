"""Define schemas and factories for gateway-backed diagnostic tools."""

from langchain_core.tools import tool
from pydantic import BaseModel, Field

from agent.tools.GatewayClient import GatewayClient


class QueryMetricsInput(BaseModel):
    """Input schema for querying templated service metrics."""

    template_key: str = Field(
        description=(
            "指标模板名，只能是 error_rate/qps/p99_latency/cpu_usage/"
            "jvm_heap_usage 之一，一次只能查一个维度"
        )
    )
    service: str = Field(description="被查询的服务名，需与 CMDB 登记的服务名一致")
    window: int = Field(
        description="查询窗口(单位：秒)，如 '120'；先用大窗口看整体，再缩小窗口配合 end_offset_seconds 定位"
    )
    compare_baseline: bool = Field(
        default=False, description="是否附带同比/环比基线用于对比"
    )


def make_query_metrics(gateway_client: GatewayClient):
    """Create a metrics query tool bound to the gateway client."""

    @tool("query_metrics", args_schema=QueryMetricsInput)
    def query_metrics(
        template_key: str, service: str, window: str, compare_baseline: bool = False
    ) -> dict:
        """Query time-series monitoring metrics for a service over a given window (via Prometheus query_range).

        Typical usage: start with a larger window (e.g. 1800s) for a coarse overview
        of whether the service looks unhealthy, then narrow the window and set
        end_offset_seconds to slide it into a suspicious time range for a closer look.
        The 5 template keys map to distinct diagnostic dimensions (traffic, errors,
        latency, memory, CPU) — only one can be queried per call.
        """
        response = gateway_client.call(
            "/metrics/query",
            {
                "templateKey": template_key,
                "service": service,
                "window": window,
                "compareBaseline": compare_baseline,
            },
        )
        return response.model_dump()

    return query_metrics


class QueryCmdbInput(BaseModel):
    """Input schema for querying service topology from CMDB."""

    service: str = Field(
        max_length=64,
        pattern=r"^[a-zA-Z0-9._-]+$",
        description=(
            "Target service name. It must match the name registered in CMDB. "
            "Only letters, digits, '.', '_', '-' are allowed."
        ),
    )


def make_query_cmdb(gateway_client: GatewayClient):
    """Create a CMDB query tool bound to the gateway client."""

    @tool("query_cmdb", args_schema=QueryCmdbInput)
    def query_cmdb(service: str) -> dict:
        """Look up the topology of a service registered in CMDB.

        Use this to confirm that a service exists and inspect its registered
        instances and topology. Do this before querying metrics, logs, or traces.
        The query tools expect the service name to match the CMDB registration.
        """
        response = gateway_client.call(
            "/cmdb/query",
            {"service": service},
        )
        return response.model_dump()

    return query_cmdb


class QueryLogsInput(BaseModel):
    """Input schema for querying and filtering service logs."""

    service: str = Field(
        max_length=64,
        pattern=r"^[a-zA-Z0-9._-]+$",
        description=(
            "Target service name, must match the service_name label used in Loki "
            "logs (same service registered in CMDB). Only letters, digits, '.', "
            "'_', '-' are allowed."
        ),
    )
    window: int = Field(
        gt=0,
        le=86400,
        description=(
            "Time window in seconds to query, must be > 0 and at most 86400 (24h). "
            "Start with a broad window for an overview, then narrow it together "
            "with end_offset_seconds to zoom into a suspicious time range."
        ),
    )
    end_offset_seconds: int | None = Field(
        default=None,
        ge=0,
        description=(
            "Optional backward offset in seconds from now, used as the query's end "
            "time (defaults to now when omitted). Combine with a narrower window to "
            "slide the query into a specific past time range — same pattern as "
            "query_metrics."
        ),
    )
    pattern: str | None = Field(
        default=None,
        max_length=200,
        description=(
            "Optional keyword or regex used to filter log lines before they're "
            "pattern-aggregated. Leave empty for a broad overview of what's "
            "happening; once you spot a suspicious pattern or known error "
            "signature, set this to narrow down to the matching lines."
        ),
    )


def make_query_logs(gateway_client: GatewayClient):
    """Create a log query tool bound to the gateway client."""

    @tool("query_logs", args_schema=QueryLogsInput)
    def query_logs(
        service: str,
        window: int,
        end_offset_seconds: int | None = None,
        pattern: str | None = None,
    ) -> dict:
        """Query logs for a service over a given time window (via Loki query_range).

        Returns pattern-aggregated log groups plus any traceIds extracted from the
        matched lines (distinctTraceIds) — feed one of those into query_trace to
        inspect the full call chain for that specific request. Start broad with no
        pattern to see the overall picture, then narrow the window (with
        end_offset_seconds) and/or set pattern to filter down once you know what
        you're looking for.
        """
        response = gateway_client.call(
            "/logs/query",
            {
                "service": service,
                "window": window,
                "endOffsetSeconds": end_offset_seconds,
                "pattern": pattern,
            },
        )
        return response.model_dump()

    return query_logs


class QueryTraceInput(BaseModel):
    """Input schema for querying a distributed trace."""

    trace_id: str = Field(
        pattern=r"^[a-f0-9]{16,32}$",
        description=(
            "Trace ID to look up, a 16-32 char lowercase hex string. Typically "
            "obtained from query_logs's response (the distinctTraceIds extracted "
            "from matched log lines) rather than guessed."
        ),
    )
    max_depth: int | None = Field(
        default=None,
        gt=0,
        le=10,
        description=(
            "Maximum depth of the returned call-chain tree, from 1 to 10. Defaults "
            "to 3 on the gateway side when omitted. Lower it to prune a very "
            "deep/wide trace down to just the top of the call chain."
        ),
    )


def make_query_trace(gateway_client: GatewayClient):
    """Create a trace query tool bound to the gateway client."""

    @tool("query_trace", args_schema=QueryTraceInput)
    def query_trace(trace_id: str, max_depth: int | None = None) -> dict:
        """Query the full call-chain tree for a trace (via Tempo), pruned to max_depth.

        Use this once you have a traceId (usually from query_logs's
        distinctTraceIds) to see how a single request propagated across
        services — which service called which, and how long each span took.
        Useful for pinpointing which downstream service in a call chain is
        actually responsible for an error or a latency spike.
        """
        response = gateway_client.call(
            "/trace/query",
            {
                "traceId": trace_id,
                "maxDepth": max_depth,
            },
        )
        return response.model_dump()

    return query_trace


class QueryMetricsRawInput(BaseModel):
    """Input schema for querying metrics with raw PromQL."""

    query: str = Field(
        max_length=500,
        description=(
            "Raw PromQL query string — an escape hatch for when none of "
            "query_metrics's template_key options fit the diagnostic need. Must "
            'contain at least one label matcher (e.g. {service="pm-auth"}) '
            "somewhere in the expression; queries without one are rejected by the "
            "gateway with a 403 to prevent unscoped full scans."
        ),
    )
    service: str = Field(
        max_length=64,
        pattern=r"^[a-zA-Z0-9._-]+$",
        description=(
            "Target service name, must match the service name registered in "
            "CMDB. Only letters, digits, '.', '_', '-' are allowed."
        ),
    )
    window: int = Field(
        gt=0,
        le=86400,
        description=(
            "Time window in seconds to query, must be > 0 and at most 86400 (24h). "
            "Same usage pattern as query_metrics: start broad, then narrow."
        ),
    )
    end_offset_seconds: int | None = Field(
        default=None,
        ge=0,
        description=(
            "Optional backward offset in seconds from now, used as the query's end "
            "time (defaults to now when omitted). Combine with a narrower window to "
            "slide the query into a specific past time range — same pattern as "
            "query_metrics."
        ),
    )


def make_query_metrics_raw(gateway_client: GatewayClient):
    """Create a raw metrics query tool bound to the gateway client."""

    @tool("query_metrics_raw", args_schema=QueryMetricsRawInput)
    def query_metrics_raw(
        query: str, service: str, window: int, end_offset_seconds: int | None = None
    ) -> dict:
        """Query time-series metrics with a raw PromQL expression (via Prometheus query_range).

        This is the escape hatch for when query_metrics's 5 template keys don't
        cover what's needed — e.g. a custom aggregation, a metric no template
        exposes, or a specific label combination. The query must include at least
        one label matcher or the gateway rejects it with a 403. This raw channel
        has its own call budget, separate from query_metrics's template channel.
        """
        response = gateway_client.call(
            "/metrics/query/raw",
            {
                "query": query,
                "service": service,
                "window": window,
                "endOffsetSeconds": end_offset_seconds,
            },
        )
        return response.model_dump()

    return query_metrics_raw


class QueryLogsRawInput(BaseModel):
    """Input schema for querying logs with raw LogQL."""

    query: str = Field(
        max_length=500,
        description=(
            "Raw LogQL query string for diagnostics that query_logs cannot express. "
            "必须包含至少一个 label selector，否则网关 403；查询必须以该 selector "
            '开头，例如 {service_name="pm-question"} |= "ERROR"。'
        ),
    )
    service: str = Field(
        max_length=64,
        pattern=r"^[a-zA-Z0-9._-]+$",
        description=(
            "Target service name, must match the service name registered in "
            "CMDB. Only letters, digits, '.', '_', '-' are allowed."
        ),
    )
    window: int = Field(
        gt=0,
        le=86400,
        description=(
            "Time window in seconds to query, must be > 0 and at most 86400 (24h). "
            "Same usage pattern as query_metrics_raw: start broad, then narrow."
        ),
    )
    end_offset_seconds: int | None = Field(
        default=None,
        ge=0,
        description=(
            "Optional backward offset in seconds from now, used as the query's end "
            "time (defaults to now when omitted). Combine with a narrower window to "
            "slide the query into a specific past time range — same pattern as "
            "query_metrics_raw."
        ),
    )


def make_query_logs_raw(gateway_client: GatewayClient):
    """Create a raw logs query tool bound to the gateway client."""

    @tool("query_logs_raw", args_schema=QueryLogsRawInput)
    def query_logs_raw(
        query: str, service: str, window: int, end_offset_seconds: int | None = None
    ) -> dict:
        """Query logs with raw LogQL only when query_logs cannot express the need.

        The query must start with a label selector containing at least one matcher,
        otherwise the gateway rejects it with a 403. This escape hatch uses the
        raw budget shared with query_metrics_raw, not the template-tool budget.
        """
        response = gateway_client.call(
            "/logs/query/raw",
            {
                "query": query,
                "service": service,
                "window": window,
                "endOffsetSeconds": end_offset_seconds,
            },
        )
        return response.model_dump()

    return query_logs_raw
