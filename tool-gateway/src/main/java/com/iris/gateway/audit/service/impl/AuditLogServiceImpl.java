package com.iris.gateway.audit.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.iris.gateway.audit.model.entity.AuditLog;
import com.iris.gateway.audit.mapper.AuditLogMapper;
import com.iris.gateway.audit.model.dto.AuditLogDTO;
import com.iris.gateway.audit.service.AuditLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 审计日志服务实现：DTO 转实体后落库 audit_log 表
 *
 * @author silas
 * @since 2026/7/14
 */
@Slf4j
@Service
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {

    @Override
    public void record(AuditLogDTO dto) {
        AuditLog entry = new AuditLog();
        entry.setTs(Instant.now().toString());
        entry.setIncidentId(dto.getIncidentId());
        entry.setAgentRole(dto.getAgentRole());
        entry.setEndpoint(dto.getEndpoint());
        entry.setTemplateOrRaw(dto.getTemplateOrRaw());
        entry.setParamsJson(dto.getParamsJson());
        entry.setUpstreamMs(dto.getUpstreamMs());
        entry.setStatus(dto.getStatus());
        entry.setResultBytes(dto.getResultBytes());
        entry.setDegraded(dto.isDegraded() ? 1 : 0);
        try {
            save(entry);
        } catch (Exception e) {
            // ponytail: 审计写失败只告警，不能反过来打挂业务请求
            log.warn("audit_log write failed, incidentId={}", dto.getIncidentId(), e);
        }
    }
}
