package com.silas.iris.toolgateway.logs.constant;

import java.util.regex.Pattern;

/**
 * Loki 日志查询路径、数据量上限与日志字段匹配规则。
 */
public final class LogsQueryConstants {

    private LogsQueryConstants() {
    }

    public static final int MAX_WINDOW_SECONDS = 24 * 60 * 60;
    public static final String QUERY_RANGE_PATH = "/loki/api/v1/query_range";
    public static final int MAX_RAW_LINES = 5000;
    public static final int MAX_PATTERN_GROUPS = 30;

    /**
     * 匹配 trace_id=xxx / traceId:"xxx" / trace-id: xxx 等常见写法。
     */
    public static final Pattern TRACE_ID_PATTERN =
            Pattern.compile("(?i)trace[_-]?id[\"']?\\s*[:=]\\s*[\"']?([a-f0-9]{16,32})");
}
