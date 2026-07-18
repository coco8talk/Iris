package com.iris.gateway.changes.vo;

/**
 * 变更事件视图：query_changes 出参中 changes 列表的元素。
 *
 * <p>字段在 SNAKE_CASE 策略下序列化为 change_id / ts / type / service / summary / operator。</p>
 *
 * @param changeId 变更唯一标识
 * @param ts       变更时间（ISO 8601）
 * @param type     变更类型
 * @param service  关联服务名
 * @param summary  变更摘要
 * @param operator 操作人
 * @author silas
 * @since 2026/7/18
 */
public record ChangeVO(
        String changeId,
        String ts,
        String type,
        String service,
        String summary,
        String operator
) {
}
