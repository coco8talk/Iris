package com.silas.iris.toolgateway.metrics.model.enums;

import com.silas.iris.toolgateway.common.exception.UnknownTemplateException;
import com.silas.iris.toolgateway.metrics.utils.MetricsTemplateUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PromQL 模板白名单：一个枚举常量对应一条模板定义，集合在编译期封闭。
 * <p>
 * {@code promqlTemplate} 只允许包含 {@code {service}} / {@code {window}} 两个占位符，
 * 由 {@link MetricsTemplateUtil#render(String, String, String)} 替换，调用方全程不接触 PromQL 原文。
 * <p>
 * {@code templateKey} 是对外契约（调用方在请求体里传的字符串），故意与枚举常量名分开维护，
 * 不用 {@code name().toLowerCase()} 推导——重命名/重排枚举常量不应该静默改变对外字符串。
 *
 * @author silas
 * @since 2026/8/1
 */
@Getter
@AllArgsConstructor
public enum MetricTemplate {

    QPS(
            "qps",
            "流量 / 请求速率",
            "sum(rate(http_server_requests_seconds_count{application=\"{service}\"}[{window}]))"),

    ERROR_RATE(
            "error_rate",
            "错误率",
            """
                    sum(rate(http_server_requests_seconds_count{application="{service}", status=~"5.."}[{window}])) \
                    / sum(rate(http_server_requests_seconds_count{application="{service}"}[{window}]))"""),

    P99_LATENCY(
            "p99_latency",
            "延迟 P99",
            "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application=\"{service}\"}[{window}])) by (le))"),

    JVM_HEAP_USAGE(
            "jvm_heap_usage",
            "饱和度（内存）",
            """
                    sum(jvm_memory_used_bytes{application="{service}", area="heap"}) \
                    / sum(jvm_memory_max_bytes{application="{service}", area="heap"})"""),

    CPU_USAGE(
            "cpu_usage",
            "饱和度（CPU）",
            "avg(process_cpu_usage{application=\"{service}\"})");

    private final String templateKey;
    private final String description;
    private final String promqlTemplate;

    /**
     * 按对外契约 {@code templateKey} 查找模板，找不到就 400（而不是把它当 PromQL 尝试执行）。
     * 不用 {@link #valueOf} 是因为 templateKey 与枚举常量名故意分开维护，见类注释。
     *
     * @throws UnknownTemplateException templateKey 不在白名单内
     */
    public static MetricTemplate fromKey(String templateKey) {
        for (MetricTemplate template : values()) {
            if (template.templateKey.equals(templateKey)) {
                return template;
            }
        }
        throw new UnknownTemplateException(templateKey);
    }
}
