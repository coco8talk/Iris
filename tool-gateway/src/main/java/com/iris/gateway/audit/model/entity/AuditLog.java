package com.iris.gateway.audit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 审计日志：记录 Agent 调用上游服务的请求信息、响应状态及性能指标
 *
 * @author silas
 * @since 2026/7/14
 */
@Data
@TableName("audit_log")
public class AuditLog {

    /** 自增主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 日志时间戳，ISO 8601 格式 */
    private String ts;

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

    /** 是否降级：0 = 未降级，1 = 已降级 */
    private Integer degraded;
}
