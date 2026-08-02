package com.silas.iris.toolgateway.common.constant;

/**
 * 鉴权相关的请求头名称、请求属性 key 等常量。
 * <p>
 * 这些字面量同时出现在 {@link com.silas.iris.toolgateway.common.interceptor.AuthInterceptor}
 * 的实际校验逻辑，以及各 controller 的 Swagger {@code @Parameter} 文档注解中，
 * 集中到此处避免两边字面量不一致。
 *
 * @author silas
 * @since 2026/8/1
 */
public final class HeaderConstants {

    private HeaderConstants() {
    }

    /**
     * 访问令牌请求头，格式：{@code Bearer <token>}。
     */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * 事件 ID 请求头。
     */
    public static final String INCIDENT_ID_HEADER = "X-Incident-Id";

    /**
     * Agent 角色请求头。
     */
    public static final String AGENT_ROLE_HEADER = "X-Agent-Role";

    /**
     * Authorization 请求头的 token 前缀。
     */
    public static final String BEARER_PREFIX = "Bearer ";

    /**
     * agentRole 在 request attribute 中的 key，供后续 controller 通过 {@code @RequestAttribute} 读取。
     */
    public static final String AGENT_ROLE_ATTR = "agentRole";

    /**
     * incidentId 在 request attribute 中的 key，供后续 controller 通过 {@code @RequestAttribute} 读取。
     */
    public static final String INCIDENT_ID_ATTR = "incidentId";

    /**
     * 当前请求对应的工具名称，供审计拦截器读取。
     */
    public static final String TOOL_NAME_ATTR = "auditToolName";

    /**
     * 当前请求使用模板通道还是 raw 通道，供审计拦截器读取。
     */
    public static final String TEMPLATE_OR_RAW_ATTR = "auditTemplateOrRaw";

    /**
     * 当前请求的审计参数摘要；未设置时审计拦截器会记录请求方法、路径和查询串。
     */
    public static final String AUDIT_REQUEST_PARAMS_ATTR = "auditRequestParams";

    /**
     * 当前响应的审计摘要；未设置时审计拦截器会根据最终状态码生成摘要。
     */
    public static final String AUDIT_RESPONSE_SUMMARY_ATTR = "auditResponseSummary";

    /**
     * 当前响应是否为降级结果，供审计拦截器读取。
     */
    public static final String AUDIT_DEGRADED_ATTR = "auditDegraded";
}
