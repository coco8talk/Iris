package com.iris.gateway;

import com.iris.gateway.metrics.AnomalyHint;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则化异常提示阈值边界：last / baseline_avg ≥ 3 才提示(T14)。
 *
 * @author silas
 * @since 2026/7/18
 */
class MetricsAnomalyHintTest {

    @Test
    void ratioExactlyThreeTriggersHint() {
        assertThat(AnomalyHint.of(30.0, 10.0)).isNotNull();
    }

    @Test
    void ratioJustBelowThreeIsNull() {
        assertThat(AnomalyHint.of(29.0, 10.0)).isNull();
    }

    @Test
    void wellAboveThresholdTriggersHint() {
        assertThat(AnomalyHint.of(100.0, 10.0)).contains("baseline");
    }

    @Test
    void noBaselineIsNull() {
        assertThat(AnomalyHint.of(100.0, null)).isNull();
    }

    @Test
    void zeroOrNegativeBaselineIsNull() {
        assertThat(AnomalyHint.of(100.0, 0.0)).isNull();
    }
}
