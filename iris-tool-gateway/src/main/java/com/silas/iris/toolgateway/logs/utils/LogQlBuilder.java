package com.silas.iris.toolgateway.logs.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 将结构化日志查询参数拼装成 LogQL。
 */
public final class LogQlBuilder {

    private LogQlBuilder() {
    }

    public static String build(String service, String pattern) {
        String selector = "{service_name=\"" + service + "\"}";
        if (StrUtil.isBlank(pattern)) {
            return selector;
        }
        return selector + " |~ \"" + escapeQuotedString(pattern) + "\"";
    }

    private static String escapeQuotedString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
