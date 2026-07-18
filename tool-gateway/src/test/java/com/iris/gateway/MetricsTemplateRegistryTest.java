package com.iris.gateway;

import com.iris.gateway.metrics.MetricsTemplateRegistry;
import com.iris.gateway.metrics.MetricsTemplateRegistry.Template;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 12 个指标模板的 PromQL 快照断言(T14)。
 *
 * @author silas
 * @since 2026/7/18
 */
class MetricsTemplateRegistryTest {

    private static final String SVC = "order-service";

    @Test
    void allTwelveTemplatesRegistered() {
        assertThat(Template.values()).hasSize(12);
    }

    @Test
    void qps() {
        assertThat(MetricsTemplateRegistry.promql(Template.QPS, SVC))
                .isEqualTo("sum(rate(http_server_requests_seconds_count{application=\"order-service\"}[1m]))");
    }

    @Test
    void errorRate() {
        assertThat(MetricsTemplateRegistry.promql(Template.ERROR_RATE, SVC))
                .isEqualTo("sum(rate(http_server_requests_seconds_count{application=\"order-service\",status=~\"5..\"}[1m])) "
                        + "/ clamp_min(sum(rate(http_server_requests_seconds_count{application=\"order-service\"}[1m])), 1e-9)");
    }

    @Test
    void p99Latency() {
        assertThat(MetricsTemplateRegistry.promql(Template.P99_LATENCY, SVC))
                .isEqualTo("histogram_quantile(0.99, sum by (le) "
                        + "(rate(http_server_requests_seconds_bucket{application=\"order-service\"}[2m]))) * 1000");
    }

    @Test
    void jvmHeapUsage() {
        assertThat(MetricsTemplateRegistry.promql(Template.JVM_HEAP_USAGE, SVC))
                .isEqualTo("sum(jvm_memory_used_bytes{application=\"order-service\",area=\"heap\"}) "
                        + "/ sum(jvm_memory_max_bytes{application=\"order-service\",area=\"heap\"})");
    }

    @Test
    void jvmGcPause() {
        assertThat(MetricsTemplateRegistry.promql(Template.JVM_GC_PAUSE, SVC))
                .isEqualTo("histogram_quantile(0.99, sum by (le) "
                        + "(rate(jvm_gc_pause_seconds_bucket{application=\"order-service\"}[2m]))) * 1000");
    }

    @Test
    void threadpoolActive() {
        assertThat(MetricsTemplateRegistry.promql(Template.THREADPOOL_ACTIVE, SVC))
                .isEqualTo("executor_active_threads{application=\"order-service\",name=\"payment.executor\"}");
    }

    @Test
    void threadpoolQueue() {
        assertThat(MetricsTemplateRegistry.promql(Template.THREADPOOL_QUEUE, SVC))
                .isEqualTo("executor_queued_tasks{application=\"order-service\",name=\"payment.executor\"}");
    }

    @Test
    void dbPoolUsage() {
        assertThat(MetricsTemplateRegistry.promql(Template.DB_POOL_USAGE, SVC))
                .isEqualTo("hikaricp_connections_active{application=\"order-service\"} "
                        + "/ hikaricp_connections_max{application=\"order-service\"}");
    }

    @Test
    void dbQueryTime() {
        assertThat(MetricsTemplateRegistry.promql(Template.DB_QUERY_TIME, SVC))
                .isEqualTo("rate(hikaricp_connections_usage_seconds_sum{application=\"order-service\"}[2m]) "
                        + "/ clamp_min(rate(hikaricp_connections_usage_seconds_count{application=\"order-service\"}[2m]), 1e-9) * 1000");
    }

    @Test
    void cpuUsage() {
        assertThat(MetricsTemplateRegistry.promql(Template.CPU_USAGE, SVC))
                .isEqualTo("process_cpu_usage{application=\"order-service\"}");
    }

    @Test
    void diskFree() {
        assertThat(MetricsTemplateRegistry.promql(Template.DISK_FREE, SVC))
                .isEqualTo("disk_free_bytes{application=\"order-service\"} "
                        + "/ disk_total_bytes{application=\"order-service\"}");
    }

    @Test
    void cacheErrorRate() {
        assertThat(MetricsTemplateRegistry.promql(Template.CACHE_ERROR_RATE, SVC))
                .isEqualTo("rate(cache_ops_total{application=\"order-service\",result=\"error\"}[5m]) "
                        + "/ clamp_min(rate(cache_ops_total{application=\"order-service\"}[5m]), 1e-9)");
    }

    @Test
    void unitsAreExposed() {
        assertThat(Template.P99_LATENCY.unit()).isEqualTo("ms");
        assertThat(Template.QPS.unit()).isEqualTo("req/s");
        assertThat(Template.ERROR_RATE.unit()).isEqualTo("ratio");
    }

    @Test
    void requireResolvesById() {
        assertThat(MetricsTemplateRegistry.require("p99_latency")).isEqualTo(Template.P99_LATENCY);
    }

    @Test
    void requireRejectsUnknownTemplate() {
        assertThatThrownBy(() -> MetricsTemplateRegistry.require("drop_table"))
                .hasMessageContaining("template");
    }
}
