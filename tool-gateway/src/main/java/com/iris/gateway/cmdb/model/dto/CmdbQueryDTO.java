package com.iris.gateway.cmdb.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * CMDB 查询请求参数
 *
 * @param template    查询模板标识：get_topology（整体拓扑）或 get_service_detail（单一服务详情）
 * @param serviceName 服务名称；仅 template 为 get_service_detail 时必填
 * @author silas
 * @since 2026/7/14
 */
@Schema(description = "CMDB 查询请求参数")
public record CmdbQueryDTO(

        @Schema(description = "查询模板标识", allowableValues = {"get_topology", "get_service_detail"},
                requiredMode = Schema.RequiredMode.REQUIRED, example = "get_topology")
        @NotBlank(message = "template 不能为空")
        String template,

        @Schema(description = "服务名称，仅 template 为 get_service_detail 时必填", example = "order-service")
        String serviceName
) {
}
