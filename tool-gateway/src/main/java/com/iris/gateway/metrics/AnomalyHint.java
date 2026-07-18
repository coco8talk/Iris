package com.iris.gateway.metrics;

/**
 * 规则化异常提示：确定性代码，绝不进 LLM(T14)。
 *
 * <p>唯一规则：当有 baseline 且 {@code last / baseline_avg ≥ 3} 时，提示当前值为基线均值的倍数；
 * 否则返回 null。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
public final class AnomalyHint {

    /** 触发提示的倍数阈值 */
    private static final double THRESHOLD = 3.0;

    private AnomalyHint() {
    }

    /**
     * 根据当前值与基线均值生成提示。
     *
     * @param last        当前窗口最后一个点的值
     * @param baselineAvg 24h 前同窗口的均值；为 null 或 ≤0 时不提示
     * @return 提示串；不满足阈值时返回 null
     */
    public static String of(double last, Double baselineAvg) {
        if (baselineAvg == null || baselineAvg <= 0) {
            return null;
        }
        double ratio = last / baselineAvg;
        if (ratio >= THRESHOLD) {
            return "last 值为 baseline 均值的 %.1f 倍".formatted(ratio);
        }
        return null;
    }
}
