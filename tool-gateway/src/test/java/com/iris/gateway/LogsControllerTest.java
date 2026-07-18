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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * query_logs 接口测试：WireMock 假 Loki，覆盖 LogQL 正确/聚合/降级/limit 越界(T15)。
 *
 * @author silas
 * @since 2026/7/18
 */
@SpringBootTest(properties = {
        "gateway.token=test-token",
        "gateway.cmdb-file=../deploy/cmdb.yaml",
        "spring.datasource.url=jdbc:sqlite:target/logs-test.db"
})
@AutoConfigureMockMvc
class LogsControllerTest {

    private static final String ENDPOINT = "/api/v1/tools/query_logs";

    private static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        WM.start();
    }

    @DynamicPropertySource
    static void lokiUrl(DynamicPropertyRegistry registry) {
        registry.add("gateway.loki-base-url", WM::baseUrl);
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

    private static String streams() {
        String l1 = "2026-07-18T08:00:00.000 ERROR [order-service," + "a".repeat(32)
                + ",s1] c.i.O - NPE at id 123";
        String l2 = "2026-07-18T08:00:01.000 ERROR [order-service," + "b".repeat(32)
                + ",s2] c.i.O - NPE at id 456";
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":"
                + "[{\"stream\":{\"container\":\"order-service\"},\"values\":["
                + "[\"1752825600000000000\",\"" + l1 + "\"],"
                + "[\"1752825601000000000\",\"" + l2 + "\"]]}]}}";
    }

    private void stubLoki(String body, int delayMs) {
        WM.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(delayMs)
                        .withBody(body)));
    }

    private MockHttpServletRequestBuilder authedPost(String body) {
        return post(ENDPOINT)
                .header("Authorization", "Bearer test-token")
                .header("X-Incident-Id", "INC-LOG-1")
                .header("X-Agent-Role", "logs")
                .contentType("application/json")
                .content(body);
    }

    /** Loki 在线：LogQL 正确、pattern 聚合、原始行含 trace_id */
    @Test
    void normalAggregatesPatternsAndBuildsLogQL() throws Exception {
        stubLoki(streams(), 0);
        mockMvc.perform(authedPost("{\"service\":\"order-service\",\"level\":\"ERROR\","
                        + "\"keyword\":\"timeout\",\"window\":\"30m\",\"limit\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.data.patterns[0].pattern").value("NPE at id <N>"))
                .andExpect(jsonPath("$.data.patterns[0].count").value(2))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].trace_id").exists());

        WM.verify(getRequestedFor(urlPathEqualTo("/loki/api/v1/query_range"))
                .withQueryParam("query", equalTo("{container=\"order-service\"} |= \"ERROR\" |= \"timeout\"")));
    }

    /** Loki 延迟 9s > 8s 超时：返回 200 + degraded:true */
    @Test
    void slowUpstreamDegrades() throws Exception {
        stubLoki(streams(), 9000);
        mockMvc.perform(authedPost("{\"service\":\"order-service\",\"window\":\"30m\",\"limit\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degraded_reason").exists());
    }

    /** limit=200 > 100：400 INVALID_PARAM */
    @Test
    void limitTooLargeReturns400() throws Exception {
        mockMvc.perform(authedPost("{\"service\":\"order-service\",\"window\":\"30m\",\"limit\":200}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAM"));
    }
}
