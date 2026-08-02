package com.silas.iris.toolgateway.common.interceptor;

import com.silas.iris.toolgateway.common.audit.AuditService;
import com.silas.iris.toolgateway.common.audit.model.entity.AuditLogEntity;
import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 在工具请求完成后记录审计日志。
 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private static final String START_TIME_ATTR = AuditInterceptor.class.getName() + ".startTime";

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuditInterceptor(AuditService auditService, ObjectMapper objectMapper) {
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object toolName = request.getAttribute(HeaderConstants.TOOL_NAME_ATTR);
        Object templateOrRaw = request.getAttribute(HeaderConstants.TEMPLATE_OR_RAW_ATTR);
        if (toolName == null || templateOrRaw == null) {
            return;
        }

        long completedAt = System.currentTimeMillis();
        Object startedAt = request.getAttribute(START_TIME_ATTR);
        long elapsedMs = startedAt instanceof Long ? completedAt - (Long) startedAt : 0L;

        auditService.record(AuditLogEntity.builder()
                .incidentId(asString(request.getAttribute(HeaderConstants.INCIDENT_ID_ATTR)))
                .agentRole(asString(request.getAttribute(HeaderConstants.AGENT_ROLE_ATTR)))
                .toolName(toolName.toString())
                .templateOrRaw(templateOrRaw.toString())
                .requestParams(resolveRequestParams(request))
                .responseSummary(resolveResponseSummary(request, response, ex))
                .degraded(Boolean.TRUE.equals(request.getAttribute(HeaderConstants.AUDIT_DEGRADED_ATTR)))
                .httpStatus(response.getStatus())
                .elapsedMs(elapsedMs)
                .createdAt(Instant.ofEpochMilli(completedAt).toString())
                .build());
    }

    private String resolveRequestParams(HttpServletRequest request) {
        Object requestParams = request.getAttribute(HeaderConstants.AUDIT_REQUEST_PARAMS_ATTR);
        if (requestParams != null) {
            return toJson(requestParams);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("method", request.getMethod());
        summary.put("path", request.getRequestURI());
        if (request.getQueryString() != null) {
            summary.put("query", request.getQueryString());
        }
        return toJson(summary);
    }

    private String resolveResponseSummary(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        Object responseSummary = request.getAttribute(HeaderConstants.AUDIT_RESPONSE_SUMMARY_ATTR);
        if (responseSummary != null) {
            return toJson(responseSummary);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("httpStatus", response.getStatus());
        if (ex != null) {
            summary.put("exception", ex.getClass().getSimpleName());
            summary.put("message", ex.getMessage());
        }
        return toJson(summary);
    }

    private String toJson(Object value) {
        if (value instanceof String) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ignored) {
            return String.valueOf(value);
        }
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
