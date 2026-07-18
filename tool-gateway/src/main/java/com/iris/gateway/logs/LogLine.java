package com.iris.gateway.logs;

/**
 * 解析后的单行日志：query_logs 出参 lines 列表的元素。
 *
 * <p>字段在 SNAKE_CASE 策略下序列化为 ts / level / msg / trace_id。</p>
 *
 * @param ts      日志时间（ISO 8601）
 * @param level   日志级别
 * @param msg     消息正文（logger 之后 " - " 后的部分）
 * @param traceId 32 位 hex traceId；无则为 null
 * @author silas
 * @since 2026/7/18
 */
public record LogLine(String ts, String level, String msg, String traceId) {
}
