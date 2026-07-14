package com.iris.gateway.audit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iris.gateway.audit.model.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * audit_log 表 Mapper，仅使用 MyBatis-Plus 通用 CRUD
 *
 * @author silas
 * @since 2026/7/14
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
