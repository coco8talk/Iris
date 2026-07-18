package com.iris.gateway.common.interceptor;

import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 内部接口鉴权拦截器：/internal/** 仅校验 Bearer Token，不要求 incident/role 头，
 * 通过后构造 internal 身份的 {@link AuthContextDTO} 供审计使用(T17)。
 *
 * @author silas
 * @since 2026/7/18
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    /** 网关访问令牌，由环境变量 TOOL_GATEWAY_TOKEN 注入，缺失即启动失败（红线-6） */
    @Value("${gateway.token}")
    private String token;

    /**
     * 仅校验 Authorization（Bearer Token），通过后注入 internal 认证上下文。
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
        request.setAttribute("authContext",
                AuthContextDTO.builder()
                        .incidentId("internal")
                        .agentRole("internal")
                        .build());
        return true;
    }
}
