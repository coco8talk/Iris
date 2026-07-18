package com.iris.gateway.logs;

import cn.hutool.core.util.StrUtil;
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
 * Loki 客户端：LogQL 构造 + query_range 拉取，8s 超时按 H3 降级(T15)。
 *
 * <p>label selector 由模板结构强制（{@code {container="<service>"}}），
 * keyword 由网关负责转义引号/反斜杠。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
@Component
public class LokiClient {

    /** 上游超时（§0.3：Loki 8s） */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final RestClient restClient;

    public LokiClient(@Value("${gateway.loki-base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * 构造 LogQL：label selector 强制，level 与 keyword 为行过滤。
     *
     * @param service 服务名（强制 container label）
     * @param level   ERROR|WARN，可空
     * @param keyword 关键字，可空（网关转义引号/反斜杠）
     * @return LogQL 串
     */
    public static String buildLogQL(String service, String level, String keyword) {
        StringBuilder logql = new StringBuilder("{container=\"").append(service).append("\"}");
        if (StrUtil.isNotBlank(level)) {
            logql.append(" |= \"").append(level).append('"');
        }
        if (StrUtil.isNotBlank(keyword)) {
            logql.append(" |= \"").append(escape(keyword)).append('"');
        }
        return logql.toString();
    }

    /** 转义 LogQL 行过滤字符串中的反斜杠与引号 */
    private static String escape(String keyword) {
        return keyword.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 执行 query_range，按时间倒序返回原始日志行（≤limit）。
     *
     * @param logql LogQL 串
     * @param start 起始时刻
     * @param end   结束时刻
     * @param limit 行数上限
     * @return 原始日志行
     * @throws UpstreamUnavailableException 超时/连接失败/上游异常
     */
    public List<RawLog> queryRange(String logql, Instant start, Instant end, int limit) {
        try {
            JsonNode root = restClient.get()
                    .uri(builder -> builder.path("/loki/api/v1/query_range")
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("limit", "{limit}")
                            .queryParam("direction", "backward")
                            .build(logql, nanos(start), nanos(end), limit))
                    .retrieve()
                    .body(JsonNode.class);
            return parse(root);
        } catch (ResourceAccessException e) {
            throw new UpstreamUnavailableException("loki timeout or unreachable", e);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException("loki error: " + e.getMessage(), e);
        }
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    private List<RawLog> parse(JsonNode root) {
        if (root == null) {
            throw new UpstreamUnavailableException("loki empty response");
        }
        List<RawLog> logs = new ArrayList<>();
        for (JsonNode stream : root.path("data").path("result")) {
            for (JsonNode value : stream.path("values")) {
                long ns = Long.parseLong(value.get(0).asText());
                Instant ts = Instant.ofEpochSecond(ns / 1_000_000_000L, ns % 1_000_000_000L);
                logs.add(new RawLog(ts, value.get(1).asText()));
            }
        }
        return logs;
    }

    /**
     * 原始日志行：Loki 返回的时间戳 + 整行文本。
     *
     * @param ts   时间戳
     * @param line 整行日志文本
     */
    public record RawLog(Instant ts, String line) {
    }
}
