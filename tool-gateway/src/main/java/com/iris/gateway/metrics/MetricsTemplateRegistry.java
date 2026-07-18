package com.iris.gateway.metrics;

import com.iris.gateway.common.exception.ApiException;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 指标模板注册表：12 个模板 → PromQL 生成 + 单位(T14)。
 *
 * <p>PromQL 为确定性生成，异常判断亦为确定性代码，绝不进 LLM。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
public final class MetricsTemplateRegistry {

    /**
     * 12 个指标模板枚举，携带对外 id 与单位。
     */
    public enum Template {
        QPS("qps", "req/s"),
        ERROR_RATE("error_rate", "ratio"),
        P99_LATENCY("p99_latency", "ms"),
        JVM_HEAP_USAGE("jvm_heap_usage", "ratio"),
        JVM_GC_PAUSE("jvm_gc_pause", "ms"),
        THREADPOOL_ACTIVE("threadpool_active", "threads"),
        THREADPOOL_QUEUE("threadpool_queue", "tasks"),
        DB_POOL_USAGE("db_pool_usage", "ratio"),
        DB_QUERY_TIME("db_query_time", "ms"),
        CPU_USAGE("cpu_usage", "ratio"),
        DISK_FREE("disk_free", "ratio"),
        CACHE_ERROR_RATE("cache_error_rate", "ratio");

        private final String id;
        private final String unit;

        Template(String id, String unit) {
            this.id = id;
            this.unit = unit;
        }

        public String id() {
            return id;
        }

        public String unit() {
            return unit;
        }
    }

    private static final Map<String, Template> BY_ID = Arrays.stream(Template.values())
            .collect(Collectors.toMap(Template::id, Function.identity()));

    private MetricsTemplateRegistry() {
    }

    /**
     * 按 id 解析模板。
     *
     * @param id 模板 id
     * @return 对应模板
     * @throws ApiException 未知模板抛 400 INVALID_PARAM
     */
    public static Template require(String id) {
        Template t = id == null ? null : BY_ID.get(id);
        if (t == null) {
            throw ApiException.invalidParam("unknown template: " + id + " (must be one of the 12 metric templates)");
        }
        return t;
    }

    /**
     * 生成模板对应的 PromQL（service 代入 application label）。
     *
     * @param template 模板
     * @param service  服务名
     * @return PromQL 表达式
     */
    public static String promql(Template template, String service) {
        String s = service;
        return switch (template) {
            case QPS -> "sum(rate(http_server_requests_seconds_count{application=\"%s\"}[1m]))".formatted(s);
            case ERROR_RATE -> ("sum(rate(http_server_requests_seconds_count{application=\"%s\",status=~\"5..\"}[1m])) "
                    + "/ clamp_min(sum(rate(http_server_requests_seconds_count{application=\"%s\"}[1m])), 1e-9)").formatted(s, s);
            case P99_LATENCY -> ("histogram_quantile(0.99, sum by (le) "
                    + "(rate(http_server_requests_seconds_bucket{application=\"%s\"}[2m]))) * 1000").formatted(s);
            case JVM_HEAP_USAGE -> ("sum(jvm_memory_used_bytes{application=\"%s\",area=\"heap\"}) "
                    + "/ sum(jvm_memory_max_bytes{application=\"%s\",area=\"heap\"})").formatted(s, s);
            case JVM_GC_PAUSE -> ("histogram_quantile(0.99, sum by (le) "
                    + "(rate(jvm_gc_pause_seconds_bucket{application=\"%s\"}[2m]))) * 1000").formatted(s);
            case THREADPOOL_ACTIVE -> "executor_active_threads{application=\"%s\",name=\"payment.executor\"}".formatted(s);
            case THREADPOOL_QUEUE -> "executor_queued_tasks{application=\"%s\",name=\"payment.executor\"}".formatted(s);
            case DB_POOL_USAGE -> ("hikaricp_connections_active{application=\"%s\"} "
                    + "/ hikaricp_connections_max{application=\"%s\"}").formatted(s, s);
            case DB_QUERY_TIME -> ("rate(hikaricp_connections_usage_seconds_sum{application=\"%s\"}[2m]) "
                    + "/ clamp_min(rate(hikaricp_connections_usage_seconds_count{application=\"%s\"}[2m]), 1e-9) * 1000").formatted(s, s);
            case CPU_USAGE -> "process_cpu_usage{application=\"%s\"}".formatted(s);
            case DISK_FREE -> ("disk_free_bytes{application=\"%s\"} "
                    + "/ disk_total_bytes{application=\"%s\"}").formatted(s, s);
            case CACHE_ERROR_RATE -> ("rate(cache_ops_total{application=\"%s\",result=\"error\"}[5m]) "
                    + "/ clamp_min(rate(cache_ops_total{application=\"%s\"}[5m]), 1e-9)").formatted(s, s);
        };
    }
}
