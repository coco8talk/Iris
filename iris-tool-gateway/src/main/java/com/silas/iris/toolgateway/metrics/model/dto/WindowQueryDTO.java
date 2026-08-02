package com.silas.iris.toolgateway.metrics.model.dto;

import cn.hutool.core.util.StrUtil;
import com.silas.iris.toolgateway.metrics.constant.MetricsQueryConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * Metrics 查询工具的请求参数：指定目标服务与时间窗口，PromQL 来源二选一——
 * {@code templateKey} 走模板渲染（{@link com.silas.iris.toolgateway.metrics.controller.MetricsController#query}），
 * {@code query} 走 raw 逃生通道，跳过模板渲染但需通过 label matcher 强校验
 * （{@link com.silas.iris.toolgateway.metrics.controller.MetricsController#queryRaw}）。
 *
 * @author silas
 * @since 2026/8/1 17:22
 */
@Data
public class WindowQueryDTO {

    @Schema(description = "模板 key，与 query 二选一", example = "qps")
    private String templateKey;

    @Size(max = MetricsQueryConstants.RAW_QUERY_MAX_LENGTH, message = "query 长度不能超过 " + MetricsQueryConstants.RAW_QUERY_MAX_LENGTH)
    @Schema(description = "裸 PromQL 查询，raw 逃生通道专用，与 templateKey 二选一",
            example = "rate(http_requests_total{service=\"pm-auth\"}[5m])")
    private String query;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "service 只允许字母数字. _ -")
    @Schema(description = "服务名", example = "pm-auth", requiredMode = Schema.RequiredMode.REQUIRED)
    private String service;

    @Positive(message = "window 必须大于 0")
    @Max(value = MetricsQueryConstants.MAX_WINDOW_SECONDS, message = "window 不能大于 24 小时")
    @Schema(description = "时间窗口(秒)", example = "60", requiredMode = Schema.RequiredMode.REQUIRED)
    private int window;

    @PositiveOrZero(message = "endOffsetSeconds 不能为负")
    @Schema(description = "查询结束时间相对当前时刻的回溯偏移量(秒);不传则默认 0，即以当前时刻为查询结束时间。" +
            "用于在粗粒度总览定位到可疑区间后，将 window 平移到过去某一段做精查，而不必强制 end=now",
            example = "0")
    private Long endOffsetSeconds;

    /**
     * templateKey 与 query 必须二选一：都不传或都传，说明调用方没有明确走哪条通道，直接 400 拒绝。
     */
    @AssertTrue(message = "templateKey 和 query 必须二选一，不能同时为空或同时提供")
    @Schema(hidden = true)
    public boolean isTemplateOrQueryProvided() {
        return StrUtil.isNotBlank(templateKey) ^ StrUtil.isNotBlank(query);
    }
}
