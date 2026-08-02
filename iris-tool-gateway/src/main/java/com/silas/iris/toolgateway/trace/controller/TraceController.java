package com.silas.iris.toolgateway.trace.controller;

import cn.hutool.core.bean.BeanUtil;
import com.silas.iris.toolgateway.common.audit.constant.AuditConstants;
import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import com.silas.iris.toolgateway.common.interceptor.AuthInterceptor;
import com.silas.iris.toolgateway.common.redis.RedisService;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import com.silas.iris.toolgateway.trace.constant.TraceQueryConstants;
import com.silas.iris.toolgateway.trace.model.dto.QueryTraceDTO;
import com.silas.iris.toolgateway.trace.model.vo.SpanRaw;
import com.silas.iris.toolgateway.trace.model.vo.TraceTreeVO;
import com.silas.iris.toolgateway.trace.utils.TraceTreeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Tempo 调用链查询工具：按 traceId 获取 OTLP span，并返回深度裁剪后的调用链树。
 */
@Slf4j
@RestController
@RequestMapping("/trace")
@Validated
@Tag(name = "Trace 查询", description = "封装 Tempo 按 traceId 查询接口，返回深度裁剪后的调用链树")
public class TraceController {

    private final RedisService redisService;

    @Value("${iris.tempo.base-url}")
    private String tempoBaseUrl;

    @Value("${iris.tempo.connect-timeout-ms:3000}")
    private int tempoConnectTimeoutMs;

    @Value("${iris.tempo.read-timeout-ms:10000}")
    private int tempoReadTimeoutMs;

    private RestClient tempoClient;

    public TraceController(RedisService redisService) {
        this.redisService = redisService;
    }

    @PostConstruct
    void initTempoClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(tempoConnectTimeoutMs);
        requestFactory.setReadTimeout(tempoReadTimeoutMs);
        this.tempoClient = RestClient.builder()
                .baseUrl(tempoBaseUrl)
                .requestFactory(requestFactory)
                .build();
        log.info("Tempo 客户端初始化完成，connectTimeoutMs: {}, readTimeoutMs: {}",
                tempoConnectTimeoutMs, tempoReadTimeoutMs);
    }

    @PostMapping("/query")
    @Operation(summary = "查询调用链", description = "按 traceId 查询 Tempo，返回拼树并按 maxDepth 裁剪后的 span 树")
    @Parameters({
            @Parameter(name = HeaderConstants.AUTHORIZATION_HEADER, description = "访问令牌，格式：Bearer <token>",
                    in = ParameterIn.HEADER, required = true),
            @Parameter(name = HeaderConstants.INCIDENT_ID_HEADER, description = "事件 ID",
                    in = ParameterIn.HEADER, required = true),
            @Parameter(name = HeaderConstants.AGENT_ROLE_HEADER, description = "Agent 角色",
                    in = ParameterIn.HEADER, required = true)
    })
    public ApiEnvelope<TraceTreeVO> query(
            @Valid @RequestBody QueryTraceDTO queryTraceDTO,
            @RequestAttribute(HeaderConstants.INCIDENT_ID_ATTR) String incidentId,
            HttpServletRequest request) {

        long startTask = System.currentTimeMillis();
        request.setAttribute(HeaderConstants.TOOL_NAME_ATTR, AuditConstants.QUERY_TRACE);
        request.setAttribute(HeaderConstants.TEMPLATE_OR_RAW_ATTR, AuditConstants.TEMPLATE);
        request.setAttribute(HeaderConstants.AUDIT_REQUEST_PARAMS_ATTR, BeanUtil.beanToMap(queryTraceDTO));
        int maxDepth = Objects.requireNonNullElse(queryTraceDTO.getMaxDepth(), TraceQueryConstants.DEFAULT_MAX_DEPTH);
        log.info("开始调用链查询，incidentId: {}, traceId: {}, maxDepth: {}",
                incidentId, queryTraceDTO.getTraceId(), maxDepth);

        // 与 query_metrics/query_logs 共用模板通道预算。
        Long remaining = redisService.consume_tool_calls(incidentId);

        log.info("开始请求 Tempo trace，incidentId: {}, traceId: {}", incidentId, queryTraceDTO.getTraceId());
        List<SpanRaw> rawSpans = fetchTrace(queryTraceDTO.getTraceId());
        TraceTreeVO data = TraceTreeUtil.buildAndPrune(queryTraceDTO.getTraceId(), rawSpans, maxDepth);

        log.info("调用链查询完成，incidentId: {}, spanCount: {}, remaining: {}, elapsedMs: {}",
                incidentId, data.getTotalSpanCount(), remaining, System.currentTimeMillis() - startTask);
        return ApiEnvelope.ok(data, buildMeta(startTask, remaining, data));
    }

    /**
     * 请求 Tempo 并把 OTLP 的 resourceSpans/scopeSpans 嵌套结构拍平成 span 列表。
     */
    private List<SpanRaw> fetchTrace(String traceId) {
        Object response = tempoClient.get()
                .uri(TraceQueryConstants.QUERY_TRACE_PATH, traceId)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(Object.class);
        return flattenTraceResponse(response);
    }

    private List<SpanRaw> flattenTraceResponse(Object response) {
        List<Map<String, Object>> resourceSpans = extractResourceSpans(response);
        List<SpanRaw> spans = new ArrayList<>();
        for (Map<String, Object> resourceSpan : resourceSpans) {
            String service = extractServiceName(asMap(resourceSpan.get("resource")));
            Object scopesValue = firstNonNull(
                    resourceSpan.get("scopeSpans"), resourceSpan.get("instrumentationLibrarySpans"));
            for (Map<String, Object> scopeSpan : asObjectMaps(scopesValue)) {
                for (Map<String, Object> span : asObjectMaps(scopeSpan.get("spans"))) {
                    spans.add(toSpanRaw(span, service));
                }
            }
        }
        return spans;
    }

    private List<Map<String, Object>> extractResourceSpans(Object response) {
        if (response instanceof List<?>) {
            return asObjectMaps(response);
        }
        Map<String, Object> responseMap = asMap(response);
        Map<String, Object> trace = asMap(responseMap.get("trace"));
        Map<String, Object> container = trace.isEmpty() ? responseMap : trace;
        Object resourceSpans = firstNonNull(container.get("resourceSpans"), container.get("batches"));
        return asObjectMaps(resourceSpans);
    }

    private SpanRaw toSpanRaw(Map<String, Object> span, String service) {
        long startTimeUnixNano = asLong(span.get("startTimeUnixNano"));
        long endTimeUnixNano = asLong(span.get("endTimeUnixNano"));
        long durationNanos = endTimeUnixNano >= startTimeUnixNano
                ? endTimeUnixNano - startTimeUnixNano
                : 0L;
        return SpanRaw.builder()
                .traceId(normalizeOtlpId(asString(firstNonNull(span.get("traceId"), span.get("traceID")))))
                .spanId(normalizeOtlpId(asString(firstNonNull(span.get("spanId"), span.get("spanID")))))
                .parentId(emptyToNull(normalizeOtlpId(asString(
                        firstNonNull(span.get("parentSpanId"), span.get("parentSpanID"))))))
                .service(service)
                .operationName(asString(span.get("name")))
                .startTimeUnixNano(startTimeUnixNano)
                .endTimeUnixNano(endTimeUnixNano)
                .durationMs(durationNanos / 1_000_000L)
                .build();
    }

    private String extractServiceName(Map<String, Object> resource) {
        for (Map<String, Object> attribute : asObjectMaps(resource.get("attributes"))) {
            if ("service.name".equals(asString(attribute.get("key")))) {
                Map<String, Object> value = asMap(attribute.get("value"));
                String service = asString(firstNonNull(value.get("stringValue"), value.get("string_value")));
                return service.isBlank() ? "unknown" : service;
            }
        }
        return "unknown";
    }

    private ApiEnvelope.Meta buildMeta(long startTask, long remaining, TraceTreeVO data) {
        return ApiEnvelope.Meta.builder()
                .elapsedMs(System.currentTimeMillis() - startTask)
                .truncated(TraceTreeUtil.isTruncated(data))
                .toolCallsRemaining(remaining)
                .build();
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
        return result;
    }

    private static List<Map<String, Object>> asObjectMaps(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(Map.class::isInstance)
                .map(TraceController::asMap)
                .toList();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = asString(value);
        return text.isBlank() ? 0L : Long.parseLong(text);
    }

    /**
     * OTLP protobuf JSON 把 bytes 型 ID 编码为 Base64；Tempo 查询参数和对外契约使用十六进制。
     */
    private static String normalizeOtlpId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        if (id.matches("(?i)^[a-f0-9]+$") && id.length() % 2 == 0) {
            return id.toLowerCase(Locale.ROOT);
        }
        try {
            return HexFormat.of().formatHex(Base64.getDecoder().decode(id));
        } catch (IllegalArgumentException ignored) {
            return id;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
