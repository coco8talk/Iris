package com.silas.iris.toolgateway.common.interceptor;

import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.file.AccessDeniedException;

/**
 * 信息校验拦截
 *
 * @author silas
 * @since 2026/7/31 19:48
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${iris.bearer-token}")
    private String bearerToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // 校验 Authorization: Bearer + 必带 X-Incident-Id/X-Agent-Role，缺失 400
        String authHeader = request.getHeader("Authorization");
        String incidentId = request.getHeader("X-Incident-Id");
        String agentRole = request.getHeader("X-Agent-Role");

        if (StrUtil.isBlank(authHeader) ||
                !StrUtil.startWith(authHeader, "Bearer ") ||
                !StrUtil.equals(authHeader.substring(7), bearerToken)) {
            throw new AccessDeniedException("无效的 Token");
        }

        if(StrUtil.isAllBlank(incidentId, agentRole)){
            throw new BadRequestException("incident_id 和 agent_role 是必需的");
        }

        request.setAttribute("incidentId", incidentId);

        return true;
    }
}
