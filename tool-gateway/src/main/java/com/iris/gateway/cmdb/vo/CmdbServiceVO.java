package com.iris.gateway.cmdb.vo;

import lombok.Data;

import java.util.Map;

/**
 * CMDB 服务视图：record_json 反序列化后的服务记录
 *
 * @author silas
 * @since 2026/7/14
 */
@Data
public class CmdbServiceVO {

    /** 服务名 */
    private String name;

    /** 服务完整记录（depends_on / middleware 等） */
    private Map<String, Object> record;
}
