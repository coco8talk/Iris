package com.iris.gateway.changes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iris.gateway.changes.model.entity.ChangeEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * change_event 表 Mapper，仅使用 MyBatis-Plus 通用 CRUD。
 *
 * @author silas
 * @since 2026/7/18
 */
@Mapper
public interface ChangeEventMapper extends BaseMapper<ChangeEvent> {
}
