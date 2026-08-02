package com.silas.iris.toolgateway.trace.constant;

/**
 * Tempo 调用链查询相关常量。
 */
public final class TraceQueryConstants {

    private TraceQueryConstants() {
    }

    public static final String QUERY_TRACE_PATH = "/api/traces/{traceId}";
    public static final int DEFAULT_MAX_DEPTH = 3;
    public static final int MAX_DEPTH_LIMIT = 10;
}
