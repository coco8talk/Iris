package com.silas.iris.toolgateway.metrics.model.enums;

import com.silas.iris.toolgateway.common.exception.UnknownTemplateException;
import com.silas.iris.toolgateway.metrics.utils.MetricsTemplateUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PromQL 模板白名单：一个枚举常量对应一条模板定义，集合在编译期封闭。
 * <p>
 * {@code promqlTemplate} 只允许包含 {@code {service}} / {@code {rate_window}} 两个占位符，
 * 由 {@link MetricsTemplateUtil#render(String, String, String)} 替换，调用方全程不接触 PromQL 原文。
 * <p>
 * {@code {rate_window}} 是 {@code rate()} 的区间长度，**不是**调用方请求里的查询时间窗口。
 * 两者语义不同：前者决定每个采样点回看多久（平滑程度），后者决定曲线覆盖的时间跨度。
 * 早期版本把请求里的 window 直接塞进 rate 区间，导致 window=1800 时每个点都是 30 分钟
 * 移动平均——突发故障被抹成缓慢爬坡，且曲线起始点回看的区间整个落在查询范围之外。
 * 现在由 {@code MetricsController} 按 step 反推，见 {@code MetricsQueryConstants#RATE_WINDOW_STEP_MULTIPLIER}。
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
            "sum(rate(http_server_requests_seconds_count{job=\"{service}\"}[{rate_window}]))"),

    ERROR_RATE(
            "error_rate",
            "错误率",
            // 分子必须套 `or vector(0)`：服务从未产生过 5xx 时，status=~"5.." 一条序列都匹配不上，
            // sum() 在空输入上返回的是空向量而不是 0，空向量参与除法后整个表达式退化成空结果集，
            // 于是"错误率为 0 的健康服务"和"服务不存在"在响应里长得一模一样，调用方无从区分。
            // 分母保持不变——它为空说明该服务确实没有任何 HTTP 流量，此时返回空结果集才是正确语义。
            """
                    (sum(rate(http_server_requests_seconds_count{job="{service}", status=~"5.."}[{rate_window}])) or vector(0)) \
                    / sum(rate(http_server_requests_seconds_count{job="{service}"}[{rate_window}]))"""),

    P99_LATENCY(
            "p99_latency",
            "延迟 P99",
            "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{job=\"{service}\"}[{rate_window}])) by (le))"),

    JVM_HEAP_USAGE(
            "jvm_heap_usage",
            "饱和度（内存）",
            """
                    sum(jvm_memory_used_bytes{job="{service}", area="heap"}) \
                    / sum(jvm_memory_max_bytes{job="{service}", area="heap"})"""),

    CPU_USAGE(
            "cpu_usage",
            "饱和度（CPU）",
            "avg(process_cpu_usage{job=\"{service}\"})");

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
