package com.silas.iris.toolgateway.trace.model.dto;

import com.silas.iris.toolgateway.trace.constant.TraceQueryConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class QueryTraceDTO {

    @NotBlank
    @Pattern(regexp = "^[a-f0-9]{16,32}$", message = "traceId 格式不合法")
    @Schema(description = "调用链唯一标识，通常来自 query_logs 返回的 distinctTraceIds",
            example = "4bf92f3577b34da6a3ce929d0e0e4736", requiredMode = Schema.RequiredMode.REQUIRED)
    private String traceId;

    @Positive(message = "maxDepth 必须大于 0")
    @Max(value = TraceQueryConstants.MAX_DEPTH_LIMIT,
            message = "maxDepth 不能超过 " + TraceQueryConstants.MAX_DEPTH_LIMIT)
    @Schema(description = "返回的调用链树最大深度；不传则使用默认值 3，超出的子树会被裁剪", example = "3")
    private Integer maxDepth;
}
