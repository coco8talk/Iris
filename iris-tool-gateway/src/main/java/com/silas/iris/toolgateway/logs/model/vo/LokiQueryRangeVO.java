package com.silas.iris.toolgateway.logs.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Loki query_range 原始响应，仅供日志模块内部解析使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LokiQueryRangeVO {

    private String status;
    private RangeData data;
    private String errorType;
    private String error;
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RangeData {
        private String resultType;
        private List<StreamResult> result;
        private QueryStats stats;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamResult {
        private Map<String, String> stream;
        private List<List<String>> values;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryStats {
        private StatsSummary summary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsSummary {
        private Long totalEntriesReturned;
        private Long totalPostFilterLines;
    }
}
