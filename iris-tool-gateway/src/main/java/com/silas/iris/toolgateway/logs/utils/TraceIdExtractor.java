package com.silas.iris.toolgateway.logs.utils;

import java.util.Optional;
import java.util.regex.Matcher;

import static com.silas.iris.toolgateway.logs.constant.LogsQueryConstants.TRACE_ID_PATTERN;

/**
 * 从常见日志字段格式中提取 traceId。
 */
public final class TraceIdExtractor {

    private TraceIdExtractor() {
    }

    public static Optional<String> extract(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Matcher matcher = TRACE_ID_PATTERN.matcher(line);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
