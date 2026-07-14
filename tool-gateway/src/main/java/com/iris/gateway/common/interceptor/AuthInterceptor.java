package com.iris.gateway.common.interceptor;

import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 网关鉴权拦截器：校验 Bearer Token 及 Agent 身份请求头，
 * 通过后构造 {@link AuthContextDTO} 存入请求属性 authContext，供下游审计使用
 *
 * @author silas
 * @since 2026/7/14
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 允许调用网关的 Agent 角色白名单 */
    private static final Set<String> ROLES =
            Set.of("lead", "metrics", "logs", "trace", "verifier");

    /** 网关访问令牌，由环境变量 TOOL_GATEWAY_TOKEN 注入，缺失即启动失败（红线-6） */
    @Value("${gateway.token}")
    private String token;

    /**
     * 依次校验 Authorization（Bearer Token）、X-Incident-Id、X-Agent-Role 请求头，
     * 任一不合法直接抛出 {@link ApiException} 中断请求。
     *
     * @return 恒为 true；校验失败通过异常短路
     */
    @Override
    public boolean preHandle(HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.equals("Bearer " + token)) {
            throw ApiException.unauthorized("invalid or missing bearer token");
        }
        String incidentId = request.getHeader("X-Incident-Id");
        if (incidentId == null || incidentId.isBlank()) {
            throw ApiException.invalidParam("missing required header X-Incident-Id");
        }
        String role = request.getHeader("X-Agent-Role");
        if (role == null || !ROLES.contains(role)) {
            throw ApiException.invalidParam("X-Agent-Role must be one of " + ROLES);
        }
        request.setAttribute("authContext",
                AuthContextDTO.builder()
                        .incidentId(incidentId)
                        .agentRole(role)
                        .build());
        return true;
    }
}