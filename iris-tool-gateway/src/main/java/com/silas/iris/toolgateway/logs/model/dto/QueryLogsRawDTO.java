package com.silas.iris.toolgateway.logs.model.dto;

import com.silas.iris.toolgateway.logs.constant.LogsQueryConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * raw LogQL 查询参数。
 */
@Data
public class QueryLogsRawDTO {

    @NotBlank
    @Size(max = LogsQueryConstants.RAW_QUERY_MAX_LENGTH,
            message = "query 长度不能超过 " + LogsQueryConstants.RAW_QUERY_MAX_LENGTH)
    @Schema(description = "原始 LogQL，必须以包含至少一个 matcher 的 label selector 开头",
            example = "{service_name=\"order-svc\"} |= \"ERROR\"",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "service 只允许字母数字. _ -")
    @Schema(description = "服务名", example = "order-svc", requiredMode = Schema.RequiredMode.REQUIRED)
    private String service;

    @Positive(message = "window 必须大于 0")
    @Max(value = LogsQueryConstants.MAX_WINDOW_SECONDS, message = "window 不能大于 24 小时")
    @Schema(description = "时间窗口(秒)", example = "300", requiredMode = Schema.RequiredMode.REQUIRED)
    private int window;

    @PositiveOrZero(message = "endOffsetSeconds 不能为负")
    @Schema(description = "查询结束时间相对当前时刻的回溯偏移量(秒)", example = "0")
    private Long endOffsetSeconds;
}
