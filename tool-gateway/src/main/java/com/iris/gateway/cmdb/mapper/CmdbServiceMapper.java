package com.iris.gateway.cmdb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iris.gateway.cmdb.model.entity.CmdbServiceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * cmdb_service 表 Mapper，仅使用 MyBatis-Plus 通用 CRUD
 *
 * @author silas
 * @since 2026/7/14
 */
@Mapper
public interface CmdbServiceMapper extends BaseMapper<CmdbServiceEntity> {
}
