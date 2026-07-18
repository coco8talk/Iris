package com.iris.gateway.changes.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * query_changes 请求参数。
 *
 * @param window  时间窗，枚举 15m|30m|1h|6h
 * @param service 服务名过滤，可选
 * @author silas
 * @since 2026/7/18
 */
@Schema(description = "变更事件查询请求参数")
public record ChangesQuery(

        @Schema(description = "时间窗", allowableValues = {"15m", "30m", "1h", "6h"},
                requiredMode = Schema.RequiredMode.REQUIRED, example = "6h")
        @NotBlank(message = "window 不能为空")
        String window,

        @Schema(description = "服务名过滤，可选", example = "order-service")
        String service
) {
}
