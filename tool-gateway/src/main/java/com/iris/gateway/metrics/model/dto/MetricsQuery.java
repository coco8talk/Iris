package com.iris.gateway.metrics.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * query_metrics 请求参数。
 *
 * @param template        指标模板 id（12 选一）
 * @param service         服务名，必须属于 CMDB 已知服务
 * @param window          时间窗 15m|30m|1h|6h（缺省按 30m）
 * @param compareBaseline 是否对比 24h 前同窗口基线
 * @author silas
 * @since 2026/7/18
 */
@Schema(description = "指标查询请求参数")
public record MetricsQuery(

        @Schema(description = "指标模板 id", requiredMode = Schema.RequiredMode.REQUIRED, example = "p99_latency")
        @NotBlank(message = "template 不能为空")
        String template,

        @Schema(description = "服务名", requiredMode = Schema.RequiredMode.REQUIRED, example = "order-service")
        @NotBlank(message = "service 不能为空")
        String service,

        @Schema(description = "时间窗", allowableValues = {"15m", "30m", "1h", "6h"}, example = "30m")
        String window,

        @Schema(description = "是否对比 24h 前基线", example = "false")
        Boolean compareBaseline
) {
}
