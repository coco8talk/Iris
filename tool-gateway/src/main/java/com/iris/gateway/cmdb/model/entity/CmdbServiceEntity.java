package com.iris.gateway.cmdb.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * CMDB 服务记录：cmdb.yaml 中单个服务的完整快照
 *
 * @author silas
 * @since 2026/7/14
 */
@Data
@TableName("cmdb_service")
public class CmdbServiceEntity {

    /** 服务名，主键，由 cmdb.yaml 提供 */
    @TableId(type = IdType.INPUT)
    private String name;

    /** 服务完整记录（JSON 字符串） */
    private String recordJson;
}
