package com.iris.gateway.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.iris.gateway.common.exception.UpstreamUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Prometheus 客户端：query_range 拉取时序，5s 超时/连接失败按 H3 降级(T14)。
 *
 * @author silas
 * @since 2026/7/18
 */
@Component
public class PrometheusClient {

    /** 上游超时（§0.3：Prometheus 5s） */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public PrometheusClient(@Value("${gateway.prometheus-base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 执行 query_range，返回首条 series 的点集与命中 series 数。
     *
     * @param promql PromQL 表达式
     * @param start  起始时刻
     * @param end    结束时刻
     * @param step   步长（决定点数）
     * @return 解析结果
     * @throws UpstreamUnavailableException 超时/连接失败/上游异常（供上层降级）
     */
    public QueryResult queryRange(String promql, Instant start, Instant end, Duration step) {
        try {
            // 以 URI 变量传参：PromQL 含 {application=...}，若作为 query 值直接拼接会被
            // UriBuilder 误判为模板占位符；用 {query} 占位并由 build 变量填充可避免。
            JsonNode root = restClient.get()
                    .uri(builder -> builder.path("/api/v1/query_range")
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("step", "{step}")
                            .build(promql, start.getEpochSecond(), end.getEpochSecond(),
                                    step.getSeconds() + "s"))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root);
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException("prometheus timeout or unreachable", e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("prometheus error: " + e.getMessage(), e);
        }
    }

    private QueryResult parse(JsonNode root) {
        if (root == null) {
            throw new UpstreamUnavailableException("prometheus empty response");
        }
        JsonNode result = root.path("data").path("result");
        int series = result.size();
        List<Point> points = new ArrayList<>();
        if (series > 0) {
            for (JsonNode v : result.get(0).path("values")) {
                long ts = v.get(0).asLong();
                double value = Double.parseDouble(v.get(1).asText());
                points.add(new Point(Instant.ofEpochSecond(ts), value));
            }
        }
        return new QueryResult(points, series);
    }
}
