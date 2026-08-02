package com.silas.iris.toolgateway.cmdb.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * CMDB 查询工具的请求参数：指定要查询的目标服务。
 *
 * @author silas
 * @since 2026/8/2
 */
@Data
public class CmdbQueryDTO {

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "service 只允许字母数字. _ -")
    @Schema(description = "服务名", example = "pm-auth", requiredMode = Schema.RequiredMode.REQUIRED)
    private String service;
}
