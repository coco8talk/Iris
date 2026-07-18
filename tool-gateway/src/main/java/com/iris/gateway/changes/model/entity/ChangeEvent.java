package com.iris.gateway.changes.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 变更事件：发版 / 配置调优 / 重启等运维动作记录，用于 F09 变更溯源(T17)。
 *
 * @author silas
 * @since 2026/7/18
 */
@Data
@TableName("change_event")
public class ChangeEvent {

    /** 变更唯一标识，主键 */
    @TableId(type = IdType.INPUT)
    private String changeId;

    /** 变更发生时间，ISO 8601 格式 */
    private String ts;

    /** 变更类型：deploy / config_change / restart 等 */
    private String type;

    /** 关联服务名 */
    private String service;

    /** 变更摘要 */
    private String summary;

    /** 变更操作人 */
    private String operator;
}
