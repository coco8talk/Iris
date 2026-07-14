package com.iris.gateway.audit.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.iris.gateway.audit.model.entity.AuditLog;
import com.iris.gateway.audit.model.dto.AuditLogDTO;

/**
 * 审计日志服务：记录 Agent 对网关工具接口的每次调用
 *
 * @author silas
 * @since 2026/7/14
 */
public interface AuditLogService extends IService<AuditLog> {

    /**
     * 记录一条审计日志，ts 自动填充，写入失败只告警、不影响主流程。
     *
     * @param dto 审计日志写入参数
     */
    void record(AuditLogDTO dto);
}
