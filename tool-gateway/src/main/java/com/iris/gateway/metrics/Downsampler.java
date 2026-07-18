package com.iris.gateway.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 降采样：把过多数据点均匀抽稀到 ≤maxPoints，保留首尾(T14)。
 *
 * @author silas
 * @since 2026/7/18
 */
public final class Downsampler {

    private Downsampler() {
    }

    /**
     * 均匀抽稀。点数 ≤maxPoints 时原样返回；否则等距抽取，含首尾。
     *
     * @param points    原始数据点（时间升序）
     * @param maxPoints 目标上限（≥2）
     * @return 抽稀后的数据点，size ≤ maxPoints
     */
    public static List<Point> downsample(List<Point> points, int maxPoints) {
        int n = points.size();
        if (n <= maxPoints) {
            return points;
        }
        List<Point> out = new ArrayList<>(maxPoints);
        double step = (double) (n - 1) / (maxPoints - 1);
        for (int i = 0; i < maxPoints; i++) {
            out.add(points.get((int) Math.round(i * step)));
        }
        return out;
    }
}
