package com.silas.iris.toolgateway.common.audit.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志实体。
 *
 * @author silas
 * @since 2026/8/2
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String incidentId;
    private String agentRole;
    private String toolName;
    private String templateOrRaw;
    private String requestParams;
    private String responseSummary;
    private Boolean degraded;
    private Integer httpStatus;
    private Long elapsedMs;
    private String createdAt;
}
