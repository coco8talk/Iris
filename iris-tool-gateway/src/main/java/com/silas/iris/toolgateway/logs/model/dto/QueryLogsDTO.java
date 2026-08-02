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
 * 日志查询参数。
 */
@Data
public class QueryLogsDTO {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "service 只允许字母数字. _ -")
    @Schema(description = "服务名，对应 Loki 日志的 service_name label",
            example = "order-svc", requiredMode = Schema.RequiredMode.REQUIRED)
    private String service;

    @Positive(message = "window 必须大于 0")
    @Max(value = LogsQueryConstants.MAX_WINDOW_SECONDS, message = "window 不能大于 24 小时")
    @Schema(description = "时间窗口(秒)", example = "300", requiredMode = Schema.RequiredMode.REQUIRED)
    private int window;

    @PositiveOrZero(message = "endOffsetSeconds 不能为负")
    @Schema(description = "查询结束时间相对当前时刻的回溯偏移量(秒)", example = "0")
    private Long endOffsetSeconds;

    @Size(max = 200, message = "pattern 长度不能超过 200")
    @Schema(description = "可选的日志内容过滤关键字/正则；为空则不过滤", example = "error")
    private String pattern;
}
