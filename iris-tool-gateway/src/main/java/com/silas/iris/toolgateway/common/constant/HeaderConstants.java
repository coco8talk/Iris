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
}
