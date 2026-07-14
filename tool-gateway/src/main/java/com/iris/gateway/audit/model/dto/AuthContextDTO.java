package com.iris.gateway.audit.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

/**
 * 认证上下文：请求通过鉴权拦截器后携带的调用方身份信息，
 * 供下游 Controller 及审计日志使用
 *
 * @author silas
 * @since 2026/7/14
 */
@Data
@Builder
public class AuthContextDTO {

    /** 关联的事件或故障唯一标识，来源于请求头 X-Incident-Id */
    @NotBlank(message = "incidentId 不能为空")
    private String incidentId;

    /** 执行调用的 Agent 角色，来源于请求头 X-Agent-Role */
    @NotBlank(message = "agentRole 不能为空")
    private String agentRole;
}
