package com.silas.iris.toolgateway.logs.controller;

import cn.hutool.core.bean.BeanUtil;
import com.silas.iris.toolgateway.cmdb.service.ServiceRegistry;
import com.silas.iris.toolgateway.common.audit.constant.AuditConstants;
import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import com.silas.iris.toolgateway.common.interceptor.AuthInterceptor;
import com.silas.iris.toolgateway.common.redis.RedisService;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import com.silas.iris.toolgateway.logs.constant.LogsQueryConstants;
import com.silas.iris.toolgateway.logs.model.dto.QueryLogsDTO;
import com.silas.iris.toolgateway.logs.model.vo.LokiQueryRangeVO;
import com.silas.iris.toolgateway.logs.model.vo.QueryLogsVO;
import com.silas.iris.toolgateway.logs.utils.LogPatternAggregator;
import com.silas.iris.toolgateway.logs.utils.LogQlBuilder;
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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * Loki 日志查询工具：返回 pattern 聚合与 traceId 提取结果。
 * 上游异常直接交给全局异常处理器，本 controller 只负责编排正常路径。
 */
@Slf4j
@RestController
@RequestMapping("/logs")
@Validated
@Tag(name = "Logs 查询", description = "封装 Loki query_range，返回 pattern 聚合 + traceId 提取结果")
public class LogsController {

    private final RedisService redisService;
    private final ServiceRegistry serviceRegistry;

    @Value("${iris.loki.base-url}")
    private String lokiBaseUrl;

    @Value("${iris.loki.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${iris.loki.read-timeout-ms:10000}")
    private int readTimeoutMs;

    private RestClient lokiClient;

    public LogsController(RedisService redisService, ServiceRegistry serviceRegistry) {
        this.redisService = redisService;
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * 初始化带显式连接和读取超时的 Loki HTTP 客户端。
     */
    @PostConstruct
    void initLokiClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.lokiClient = RestClient.builder()
                .baseUrl(lokiBaseUrl)
                .requestFactory(requestFactory)
                .build();
        log.info("Loki 客户端初始化完成，connectTimeoutMs: {}, readTimeoutMs: {}",
                connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 查询指定服务与时间窗口内的日志。
     *
     * @param dto        日志查询参数
     * @param incidentId 当前事件 ID，由 {@link AuthInterceptor} 写入 request attribute
     * @param request    当前 HTTP 请求，用于写入审计上下文属性
     * @return pattern 聚合与 traceId 提取结果
     */
    @PostMapping("/query")
    @Operation(summary = "查询日志", description = "查询 Loki 日志并返回 pattern 聚合与 traceId 提取结果")
    @Parameters({
            @Parameter(name = HeaderConstants.AUTHORIZATION_HEADER,
                    description = "访问令牌，格式：Bearer <token>", in = ParameterIn.HEADER, required = true),
            @Parameter(name = HeaderConstants.INCIDENT_ID_HEADER,
                    description = "事件 ID", in = ParameterIn.HEADER, required = true),
            @Parameter(name = HeaderConstants.AGENT_ROLE_HEADER,
                    description = "Agent 角色", in = ParameterIn.HEADER, required = true)
    })
    public ApiEnvelope<QueryLogsVO> query(
            @Valid @RequestBody QueryLogsDTO dto,
            @RequestAttribute(HeaderConstants.INCIDENT_ID_ATTR) String incidentId,
            HttpServletRequest request) {

        long startTask = System.currentTimeMillis();
        request.setAttribute(HeaderConstants.TOOL_NAME_ATTR, AuditConstants.QUERY_LOGS);
        request.setAttribute(HeaderConstants.TEMPLATE_OR_RAW_ATTR, AuditConstants.TEMPLATE);
        request.setAttribute(HeaderConstants.AUDIT_REQUEST_PARAMS_ATTR, BeanUtil.beanToMap(dto));
        log.info("开始日志查询，incidentId: {}, service: {}, window: {}, pattern: {}",
                incidentId, dto.getService(), dto.getWindow(), dto.getPattern());

        serviceRegistry.requireExists(dto.getService());
        Long remaining = redisService.consume_tool_calls(incidentId);

        String logQL = LogQlBuilder.build(dto.getService(), dto.getPattern());
        TimeRange range = resolveTimeRange(dto.getWindow(), dto.getEndOffsetSeconds());
        URI uri = buildQueryRangeUri(logQL, range);
        log.info("开始请求 Loki query_range，incidentId: {}", incidentId);
        LokiQueryRangeVO raw = fetchQueryRange(uri);

        LogPatternAggregator.AggregationResult aggregation = LogPatternAggregator.aggregate(raw);
        log.info("日志查询完成，incidentId: {}, totalLines: {}, patternGroups: {}, remaining: {}, elapsedMs: {}",
                incidentId, aggregation.data().getTotalLines(), aggregation.data().getPatterns().size(),
                remaining, System.currentTimeMillis() - startTask);
        return ApiEnvelope.ok(aggregation.data(), buildMeta(startTask, remaining, aggregation.truncated()));
    }

    private TimeRange resolveTimeRange(int window, Long endOffsetSeconds) {
        Instant end = Instant.now().minusSeconds(Objects.requireNonNullElse(endOffsetSeconds, 0L));
        return new TimeRange(end.minusSeconds(window), end);
    }

    private URI buildQueryRangeUri(String logQL, TimeRange range) {
        return UriComponentsBuilder.fromPath(LogsQueryConstants.QUERY_RANGE_PATH)
                .queryParam("query", logQL)
                .queryParam("start", range.start().toString())
                .queryParam("end", range.end().toString())
                .queryParam("limit", LogsQueryConstants.MAX_RAW_LINES)
                .queryParam("direction", "backward")
                .build()
                .encode()
                .toUri();
    }

    private LokiQueryRangeVO fetchQueryRange(URI uri) {
        return lokiClient.get()
                .uri(uri)
                .retrieve()
                .body(LokiQueryRangeVO.class);
    }

    private ApiEnvelope.Meta buildMeta(long startTask, long remaining, boolean truncated) {
        return ApiEnvelope.Meta.builder()
                .elapsedMs(System.currentTimeMillis() - startTask)
                .truncated(truncated)
                .toolCallsRemaining(remaining)
                .build();
    }

    private record TimeRange(Instant start, Instant end) {
    }
}
