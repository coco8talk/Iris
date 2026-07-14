package com.iris.gateway.audit.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 审计日志写入参数，ts 由 service 自动填充
 *
 * @author silas
 * @since 2026/7/14
 */
@Data
@Builder
public class AuditLogDTO {

    /** 关联的事件或故障唯一标识 */
    private String incidentId;

    /** 执行调用的 Agent 角色：planner / executor / reviewer */
    private String agentRole;

    /** 调用的上游服务端点 */
    private String endpoint;

    /** 请求使用的模板标识或原始请求内容 */
    private String templateOrRaw;

    /** 请求参数（JSON 字符串） */
    private String paramsJson;

    /** 上游调用耗时（毫秒） */
    private Long upstreamMs;

    /** 调用结果状态码 */
    private Integer status;

    /** 返回结果大小（字节） */
    private Long resultBytes;

    /** 是否降级 */
    private boolean degraded;
}
