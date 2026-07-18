package com.iris.gateway;

import com.iris.gateway.logs.TraceIdExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * traceId 提取：T4 统一日志格式样例(T15)。
 *
 * @author silas
 * @since 2026/7/18
 */
class TraceIdExtractorTest {

    @Test
    void extractsTraceIdFromT4FormatLine() {
        String traceId = "3f2a1b4c5d6e7f8091a2b3c4d5e6f708";
        String line = "2026-07-18T08:00:00.123 ERROR [order-service," + traceId
                + ",a1b2c3d4] c.i.OrderService - NPE at line 42";
        assertThat(TraceIdExtractor.extract(line)).isEqualTo(traceId);
    }

    @Test
    void returnsNullWhenTraceIdAbsent() {
        String line = "2026-07-18T08:00:00.123 ERROR [order-service,,] c.i.OrderService - boom";
        assertThat(TraceIdExtractor.extract(line)).isNull();
    }

    @Test
    void returnsNullForPlainLine() {
        assertThat(TraceIdExtractor.extract("no brackets here")).isNull();
    }

    @Test
    void nullSafe() {
        assertThat(TraceIdExtractor.extract(null)).isNull();
    }
}
