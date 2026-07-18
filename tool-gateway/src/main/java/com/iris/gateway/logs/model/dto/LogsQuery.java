package com.iris.gateway.logs.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * query_logs 请求参数。
 *
 * @param service 服务名，必填（强制 container label）
 * @param level   日志级别 ERROR|WARN，可空
 * @param keyword 关键字过滤，可空
 * @param window  时间窗 15m|30m|1h|6h（缺省 30m）
 * @param limit   行数上限 1–100（缺省 50）
 * @author silas
 * @since 2026/7/18
 */
@Schema(description = "日志查询请求参数")
public record LogsQuery(

        @Schema(description = "服务名", requiredMode = Schema.RequiredMode.REQUIRED, example = "order-service")
        @NotBlank(message = "service 不能为空")
        String service,

        @Schema(description = "日志级别", allowableValues = {"ERROR", "WARN"}, example = "ERROR")
        String level,

        @Schema(description = "关键字过滤", example = "timeout")
        String keyword,

        @Schema(description = "时间窗", allowableValues = {"15m", "30m", "1h", "6h"}, example = "30m")
        String window,

        @Schema(description = "行数上限 1-100", example = "50")
        Integer limit
) {
}
