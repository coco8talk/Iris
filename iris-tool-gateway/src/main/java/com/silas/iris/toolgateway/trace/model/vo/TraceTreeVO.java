package com.silas.iris.toolgateway.trace.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "调用链树（裁剪后）")
public class TraceTreeVO {

    @Schema(description = "调用链唯一标识")
    private String traceId;

    @Schema(description = "裁剪前该 trace 下的 span 总数")
    private int totalSpanCount;

    @Schema(description = "本次实际生效的裁剪深度")
    private int appliedMaxDepth;

    @Schema(description = "裁剪后的树根节点")
    private SpanNode root;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单个 span 节点")
    public static class SpanNode {

        @Schema(description = "span 唯一标识")
        private String spanId;

        @Schema(description = "该 span 所属服务名")
        private String service;

        @Schema(description = "操作名，如 HTTP 方法+路径、SQL 摘要")
        private String operationName;

        @Schema(description = "该 span 耗时，单位毫秒")
        private long durationMs;

        @Schema(description = "子 span 列表，深度超过 maxDepth 时为空列表")
        private List<SpanNode> children;

        @Schema(description = "该节点因深度裁剪被丢弃的直接子节点数量；0 表示未发生裁剪")
        private int prunedChildCount;
    }
}
