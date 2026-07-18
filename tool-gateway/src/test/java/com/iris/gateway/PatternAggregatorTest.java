package com.iris.gateway;

import com.iris.gateway.logs.LogLine;
import com.iris.gateway.logs.PatternAggregator;
import com.iris.gateway.logs.vo.LogPatternVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志模板聚合：数字/HEX/UUID 归一分组计数、样本 traceId 上限(T15)。
 *
 * @author silas
 * @since 2026/7/18
 */
class PatternAggregatorTest {

    private LogLine line(String ts, String msg, String traceId) {
        return new LogLine(ts, "ERROR", msg, traceId);
    }

    @Test
    void digitsNormalizedAndGrouped() {
        List<LogPatternVO> out = PatternAggregator.aggregate(List.of(
                line("2026-07-18T08:00:00Z", "user 123 failed", "a".repeat(32)),
                line("2026-07-18T08:00:01Z", "user 456 failed", "b".repeat(32))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).pattern()).isEqualTo("user <N> failed");
        assertThat(out.get(0).count()).isEqualTo(2);
    }

    @Test
    void hex32And16NormalizedToHex() {
        assertThat(PatternAggregator.normalize("token " + "a1b2c3d4".repeat(4) + " invalid"))
                .isEqualTo("token <HEX> invalid");
        assertThat(PatternAggregator.normalize("token " + "a1b2c3d4".repeat(2) + " invalid"))
                .isEqualTo("token <HEX> invalid");
    }

    @Test
    void uuidNormalized() {
        assertThat(PatternAggregator.normalize("id 550e8400-e29b-41d4-a716-446655440000 missing"))
                .isEqualTo("id <UUID> missing");
    }

    @Test
    void sortedByCountDescending() {
        List<LogPatternVO> out = PatternAggregator.aggregate(List.of(
                line("2026-07-18T08:00:00Z", "rare event", null),
                line("2026-07-18T08:00:01Z", "common event 1", null),
                line("2026-07-18T08:00:02Z", "common event 2", null),
                line("2026-07-18T08:00:03Z", "common event 3", null)));
        assertThat(out).hasSize(2);
        assertThat(out.get(0).pattern()).isEqualTo("common event <N>");
        assertThat(out.get(0).count()).isEqualTo(3);
        assertThat(out.get(0).firstSeen()).isEqualTo("2026-07-18T08:00:01Z");
        assertThat(out.get(0).lastSeen()).isEqualTo("2026-07-18T08:00:03Z");
    }

    @Test
    void sampleTraceIdsCappedAtThreeAndDistinct() {
        List<LogPatternVO> out = PatternAggregator.aggregate(List.of(
                line("2026-07-18T08:00:00Z", "boom 1", "a".repeat(32)),
                line("2026-07-18T08:00:01Z", "boom 2", "b".repeat(32)),
                line("2026-07-18T08:00:02Z", "boom 3", "c".repeat(32)),
                line("2026-07-18T08:00:03Z", "boom 4", "d".repeat(32)),
                line("2026-07-18T08:00:04Z", "boom 5", "a".repeat(32))));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).sampleTraceIds()).hasSize(3);
        assertThat(out.get(0).sampleTraceIds()).doesNotHaveDuplicates();
    }
}
