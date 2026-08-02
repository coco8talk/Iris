package com.silas.iris.toolgateway.metrics.constant;

import java.util.regex.Pattern;

/**
 * Metrics 查询工具（query_range）相关的取值边界与固定端点常量。
 *
 * @author silas
 * @since 2026/8/1
 */
public final class MetricsQueryConstants {

    private MetricsQueryConstants() {
    }

    /**
     * 目标服务的原生 scrape_interval 默认值，用于 step 反推；
     * todo 未核实前为默认值，M0 的 prometheus.yml 落地后必须回填真实值，不能沿用任何猜测的默认值。
     */
    public static final int DEFAULT_NATIVE_SCRAPE_INTERVAL_SECONDS = 15;

    /**
     * 单次查询允许返回的最大点数，用于 step 反推与截断判定。
     */
    public static final int MAX_SAMPLE_POINTS = 30;

    /**
     * query_range 请求允许返回的最大序列数。
     */
    public static final int RESULT_SERIES_LIMIT = 20;

    /**
     * 单次查询允许的最大时间窗口（秒），对应 24 小时。
     */
    public static final int MAX_WINDOW_SECONDS = 24 * 60 * 60;

    /**
     * Prometheus query_range 接口路径。
     */
    public static final String QUERY_RANGE_PATH = "/api/v1/query_range";

    /**
     * raw 逃生通道允许的裸 PromQL 查询最大长度。
     */
    public static final int RAW_QUERY_MAX_LENGTH = 500;

    /**
     * raw 通道 label matcher 校验正则：全串搜索（非锚定开头），匹配
     * {@code {label="value"}}/{@code label!="value"}/{@code label=~"regex"}/{@code label!~"regex"}。
     */
    public static final Pattern RAW_PROMQL_LABEL_MATCHER =
            Pattern.compile("\\{[^}]*[a-zA-Z_][a-zA-Z0-9_]*\\s*(=~|!~|!=|=)\\s*\"[^\"]*\"[^}]*}");
}
