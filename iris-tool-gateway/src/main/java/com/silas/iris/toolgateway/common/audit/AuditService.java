package com.silas.iris.toolgateway.common.audit;

import com.silas.iris.toolgateway.common.audit.mapper.AuditLogMapper;
import com.silas.iris.toolgateway.common.audit.model.entity.AuditLogEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 工具调用审计服务。
 *
 * @author silas
 * @since 2026/8/2
 */
@Slf4j
@Service
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    public AuditService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 持久化一条工具调用审计记录。
     *
     * @param auditLog 审计日志
     */
    public void record(AuditLogEntity auditLog) {
        try {
            auditLogMapper.insert(auditLog);
        } catch (Exception exception) {
            log.error("审计日志写入失败，incidentId: {}, toolName: {}",
                    auditLog.getIncidentId(), auditLog.getToolName(), exception);
        }
    }
}
