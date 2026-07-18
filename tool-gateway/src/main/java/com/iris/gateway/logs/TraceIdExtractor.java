package com.iris.gateway.logs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * traceId 提取：按 T4 统一日志格式契约，traceId 为 32 位 hex，
 * 位于第一个 {@code [appName,traceId,spanId]} 段内(T15)。
 *
 * @author silas
 * @since 2026/7/18
 */
public final class TraceIdExtractor {

    /** 匹配 [appName,<32hex>, 段，捕获 traceId */
    private static final Pattern TRACE_ID = Pattern.compile("\\[[\\w-]+,([0-9a-f]{32}),");

    private TraceIdExtractor() {
    }

    /**
     * 从日志行提取 traceId。
     *
     * @param line 原始日志行
     * @return 32 位 hex traceId；不含则返回 null
     */
    public static String extract(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = TRACE_ID.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
