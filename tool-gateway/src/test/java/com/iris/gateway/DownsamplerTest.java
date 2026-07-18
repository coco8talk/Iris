package com.iris.gateway;

import com.iris.gateway.metrics.Downsampler;
import com.iris.gateway.metrics.Point;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降采样：100 点 → ≤30 点，保留首尾(T14)。
 *
 * @author silas
 * @since 2026/7/18
 */
class DownsamplerTest {

    private List<Point> points(int n) {
        Instant base = Instant.parse("2026-07-18T00:00:00Z");
        List<Point> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Point(base.plusSeconds(i * 60L), i));
        }
        return list;
    }

    @Test
    void hundredPointsDownsampledToAtMost30() {
        List<Point> out = Downsampler.downsample(points(100), 30);
        assertThat(out).hasSizeLessThanOrEqualTo(30);
        assertThat(out).isNotEmpty();
    }

    @Test
    void firstAndLastPreserved() {
        List<Point> in = points(100);
        List<Point> out = Downsampler.downsample(in, 30);
        assertThat(out.get(0)).isEqualTo(in.get(0));
        assertThat(out.get(out.size() - 1)).isEqualTo(in.get(in.size() - 1));
    }

    @Test
    void underLimitReturnedUnchanged() {
        List<Point> in = points(12);
        assertThat(Downsampler.downsample(in, 30)).isEqualTo(in);
    }

    @Test
    void emptyStaysEmpty() {
        assertThat(Downsampler.downsample(List.of(), 30)).isEmpty();
    }
}
