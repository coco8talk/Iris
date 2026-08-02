package com.silas.iris.toolgateway.common.interceptor;

import cn.hutool.core.util.StrUtil;
import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import com.silas.iris.toolgateway.common.exception.MissingRequiredHeaderException;
import com.silas.iris.toolgateway.common.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全局鉴权拦截器：校验 {@code Authorization} Bearer token，并要求必带
 * {@code X-Incident-Id}/{@code X-Agent-Role} 请求头，校验通过后写入 request attribute
 * 供后续 controller 通过 {@code @RequestAttribute} 读取。
 * <p>
 * 校验失败统一抛出异常，交给
 * {@link com.silas.iris.toolgateway.common.web.GlobalExceptionHandler} 映射成 401/400。
 * 拦截范围由 {@link com.silas.iris.toolgateway.common.config.WebConfig} 注册。
 *
 * @author silas
 * @since 2026/7/31 19:48
 */
@Component
@SuppressWarnings("all")
public class AuthInterceptor implements HandlerInterceptor {

    @Value("${iris.bearer-token}")
    private String bearerToken;

    /**
     * 请求进入 controller 前执行的鉴权与必需请求头校验。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应，本拦截器未使用
     * @param handler  即将处理该请求的 handler，本拦截器未使用
     * @return 始终为 true；校验失败通过抛出异常中断请求，不通过返回值中断
     * @throws UnauthorizedException           Authorization 缺失、格式不对或 token 不匹配
     * @throws MissingRequiredHeaderException  X-Incident-Id 和 X-Agent-Role 同时缺失
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        // 校验 Authorization: Bearer + 必带 X-Incident-Id/X-Agent-Role，缺失 400
        String authHeader = request.getHeader(HeaderConstants.AUTHORIZATION_HEADER);
        String incidentIdValue = request.getHeader(HeaderConstants.INCIDENT_ID_HEADER);
        String agentRole = request.getHeader(HeaderConstants.AGENT_ROLE_HEADER);

        if (StrUtil.isBlank(authHeader) ||
                !StrUtil.startWith(authHeader, HeaderConstants.BEARER_PREFIX) ||
                !StrUtil.equals(authHeader.substring(HeaderConstants.BEARER_PREFIX.length()), bearerToken)) {
            throw new UnauthorizedException("无效的 Token");
        }

        if(StrUtil.hasBlank(incidentIdValue, agentRole)){
            throw new MissingRequiredHeaderException("X-Incident-Id 和 X-Agent-Role 是必需的");
        }

        request.setAttribute(HeaderConstants.INCIDENT_ID_ATTR, incidentIdValue);
        request.setAttribute(HeaderConstants.AGENT_ROLE_ATTR, agentRole);

        return true;
    }
}
