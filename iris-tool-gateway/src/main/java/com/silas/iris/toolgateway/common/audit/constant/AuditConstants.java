package com.silas.iris.toolgateway.common.audit.constant;

/**
 * 审计日志中的固定工具名与调用通道标识。
 *
 * @author silas
 * @since 2026/8/2
 */
public final class AuditConstants {

    private AuditConstants() {
    }

    public static final String QUERY_METRICS = "query_metrics";
    public static final String QUERY_LOGS = "query_logs";
    public static final String QUERY_TRACE = "query_trace";
    public static final String TEMPLATE = "template";
    public static final String RAW = "raw";
}
