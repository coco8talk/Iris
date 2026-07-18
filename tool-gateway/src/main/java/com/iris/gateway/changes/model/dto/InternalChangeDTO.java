package com.iris.gateway.changes.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * /internal/changes 写入参数：供 injector 写入伪装变更。
 *
 * <p>change_id、ts 可空（缺省时网关分别生成 UUID / 当前时刻）。</p>
 *
 * @param changeId 变更唯一标识，可空
 * @param ts       变更时间（ISO 8601），可空
 * @param type     变更类型，必填
 * @param service  关联服务名，必填
 * @param summary  变更摘要，必填
 * @param operator 操作人，必填
 * @author silas
 * @since 2026/7/18
 */
@Schema(description = "内部变更写入参数")
public record InternalChangeDTO(

        @Schema(description = "变更唯一标识，缺省则生成 UUID")
        String changeId,

        @Schema(description = "变更时间(ISO 8601)，缺省则用当前时刻")
        String ts,

        @Schema(description = "变更类型", requiredMode = Schema.RequiredMode.REQUIRED, example = "config_change")
        @NotBlank(message = "type 不能为空")
        String type,

        @Schema(description = "关联服务名", requiredMode = Schema.RequiredMode.REQUIRED, example = "payment-service")
        @NotBlank(message = "service 不能为空")
        String service,

        @Schema(description = "变更摘要", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "summary 不能为空")
        String summary,

        @Schema(description = "操作人", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "operator 不能为空")
        String operator
) {
}
