package com.silas.iris.toolgateway.logs.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 日志 pattern 聚合结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "日志 pattern 聚合结果")
public class QueryLogsVO {

    @Schema(description = "本次窗口内 Loki 实际返回的原始日志行数（聚合前）")
    private int totalLines;

    @Schema(description = "聚合后的日志模式列表，按 count 降序")
    private List<PatternGroup> patterns;

    @Schema(description = "本次窗口内提取到的全部去重 traceId，可直接作为 query_trace 的入参")
    private List<String> distinctTraceIds;

    /**
     * 单个归一化日志模式。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单个日志模式的聚合结果")
    public static class PatternGroup {

        @Schema(description = "归一化后的日志模式骨架")
        private String pattern;

        @Schema(description = "该模式在窗口内出现的次数")
        private long count;

        @Schema(description = "该模式的一条原始日志示例")
        private String sampleLine;

        @Schema(description = "该模式下提取到的 traceId（去重）")
        private List<String> traceIds;
    }
}
