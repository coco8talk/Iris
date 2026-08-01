package com.silas.iris.toolgateway.matrics.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Prometheus query_range 接口的请求参数，对应
 * {@code GET /api/v1/query_range?query=&start=&end=&step=...}。
 * 由 PrometheusClient 拼装成上游 HTTP 请求的 query string，不直接作为 JSON body 序列化。
 *
 * @author silas
 * @since 2026/7/31 17:25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Prometheus query_range 请求参数")
public class QueryRangeDTO {

    @Schema(description = "PromQL 查询表达式", example = "up",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "query 不能为空")
    private String query;

    @Schema(description = "查询起始时间（含），支持 RFC3339 字符串或 Unix 时间戳",
            example = "2026-07-31T00:00:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "start 不能为空")
    private String start;

    @Schema(description = "查询结束时间（含），支持 RFC3339 字符串或 Unix 时间戳",
            example = "2026-07-31T00:30:00Z", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "end 不能为空")
    private String end;

    @Schema(description = "查询分辨率步长，支持 duration 格式（如 30s）或浮点秒数",
            example = "30s", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "step 不能为空")
    private String step;

    @Schema(description = "查询求值超时时间，duration 格式；不传则使用 Prometheus 侧默认值，"
            + "并受其 -query.timeout 上限约束", example = "5s")
    private String timeout;

    @Schema(description = "返回的最大序列数，0 表示不限制", example = "0")
    @PositiveOrZero(message = "limit 不能为负数")
    private Integer limit;

    @Schema(description = "覆盖本次查询的回溯窗口，duration 格式或浮点秒数；"
            + "对应 Prometheus 的 lookback_delta 参数", example = "5m")
    private String lookbackDelta;

    @Schema(description = "是否在响应中附带查询统计信息，取值如 all", example = "all")
    private String stats;
}
