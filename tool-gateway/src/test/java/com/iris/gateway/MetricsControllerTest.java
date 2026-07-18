package com.iris.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * query_metrics 接口测试：WireMock 假 Prometheus，覆盖正常/降级/越界/未知服务/baseline(T14)。
 *
 * @author silas
 * @since 2026/7/18
 */
@SpringBootTest(properties = {
        "gateway.token=test-token",
        "gateway.cmdb-file=../deploy/cmdb.yaml",
        "spring.datasource.url=jdbc:sqlite:target/metrics-test.db"
})
@AutoConfigureMockMvc
class MetricsControllerTest {

    private static final String ENDPOINT = "/api/v1/tools/query_metrics";

    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        WM.start();
    }

    @DynamicPropertySource
    static void prometheusUrl(DynamicPropertyRegistry registry) {
        registry.add("gateway.prometheus-base-url", WM::baseUrl);
    }

    @AfterAll
    static void stopWireMock() {
        WM.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void resetStubs() {
        WM.resetAll();
    }

    /** 构造含 40 点单 series 的 Prometheus matrix 响应 */
    private static String matrix(int n) {
        StringBuilder values = new StringBuilder();
        long t0 = 1_752_800_000L;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append('[').append(t0 + i * 60L).append(",\"").append(i + 1).append("\"]");
        }
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":"
                + "[{\"metric\":{\"application\":\"order-service\"},\"values\":[" + values + "]}]}}";
    }

    private void stubPrometheus(String body, int delayMs) {
        WM.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(delayMs)
                        .withBody(body)));
    }

    private MockHttpServletRequestBuilder authedPost(String body) {
        return post(ENDPOINT)
                .header("Authorization", "Bearer test-token")
                .header("X-Incident-Id", "INC-MET-1")
                .header("X-Agent-Role", "metrics")
                .contentType("application/json")
                .content(body);
    }

    /** Prometheus 在线：返回统计摘要与降采样点(≤30) */
    @Test
    void normalReturnsStatsAndPoints() throws Exception {
        stubPrometheus(matrix(40), 0);
        mockMvc.perform(authedPost("{\"template\":\"p99_latency\",\"service\":\"order-service\","
                        + "\"window\":\"30m\",\"compare_baseline\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.data.template").value("p99_latency"))
                .andExpect(jsonPath("$.data.unit").value("ms"))
                .andExpect(jsonPath("$.data.stats.avg").exists())
                .andExpect(jsonPath("$.data.points").isArray())
                .andExpect(jsonPath("$.data.points[0]").exists());
    }

    /** Prometheus 延迟 6s > 5s 超时：返回 200 + degraded:true */
    @Test
    void slowUpstreamDegrades() throws Exception {
        stubPrometheus(matrix(10), 6000);
        mockMvc.perform(authedPost("{\"template\":\"qps\",\"service\":\"order-service\","
                        + "\"window\":\"30m\",\"compare_baseline\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degraded_reason").exists());
    }

    /** window=12h 超过 6h 上限：400 RANGE_TOO_LARGE */
    @Test
    void windowTooLargeReturns400() throws Exception {
        mockMvc.perform(authedPost("{\"template\":\"qps\",\"service\":\"order-service\","
                        + "\"window\":\"12h\",\"compare_baseline\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
    }

    /** 未知 service（不在 CMDB）：400 INVALID_PARAM */
    @Test
    void unknownServiceReturns400() throws Exception {
        mockMvc.perform(authedPost("{\"template\":\"qps\",\"service\":\"nope-service\","
                        + "\"window\":\"30m\",\"compare_baseline\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }

    /** compare_baseline=true：附带 baseline_stats */
    @Test
    void baselineComparisonIncludesBaselineStats() throws Exception {
        stubPrometheus(matrix(40), 0);
        mockMvc.perform(authedPost("{\"template\":\"p99_latency\",\"service\":\"order-service\","
                        + "\"window\":\"30m\",\"compare_baseline\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseline_stats").exists());
    }
}
