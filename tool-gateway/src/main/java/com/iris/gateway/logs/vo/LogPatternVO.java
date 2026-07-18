package com.iris.gateway.logs.vo;

import java.util.List;

/**
 * 日志模板聚合项：query_logs 出参 patterns 列表的元素。
 *
 * @param pattern        归一化后的模板串
 * @param count          出现次数
 * @param firstSeen      首次出现时间（ISO 8601）
 * @param lastSeen       末次出现时间（ISO 8601）
 * @param sampleTraceIds 样本 traceId（≤3）
 * @author silas
 * @since 2026/7/18
 */
public record LogPatternVO(
        String pattern,
        long count,
        String firstSeen,
        String lastSeen,
        List<String> sampleTraceIds
) {
}
