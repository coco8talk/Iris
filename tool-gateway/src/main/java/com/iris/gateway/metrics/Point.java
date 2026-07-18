package com.iris.gateway.metrics;

import java.time.Instant;

/**
 * 时序数据点：时间戳 + 数值。
 *
 * @param ts    时间戳
 * @param value 数值
 * @author silas
 * @since 2026/7/18
 */
public record Point(Instant ts, double value) {
}
