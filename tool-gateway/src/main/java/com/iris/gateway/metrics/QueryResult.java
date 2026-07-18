package com.iris.gateway.metrics;

import java.util.List;

/**
 * Prometheus query_range 解析结果：首条 series 的数据点 + 命中 series 总数。
 *
 * @param points      首条 series 的数据点
 * @param seriesCount 命中的 series 总数（用于判定是否超 20 截断）
 * @author silas
 * @since 2026/7/18
 */
public record QueryResult(List<Point> points, int seriesCount) {
}
