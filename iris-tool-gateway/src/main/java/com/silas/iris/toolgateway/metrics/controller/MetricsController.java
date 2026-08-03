package com.silas.iris.toolgateway.metrics.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.silas.iris.toolgateway.common.audit.constant.AuditConstants;
import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import com.silas.iris.toolgateway.common.exception.RawQueryRejectedException;
import com.silas.iris.toolgateway.common.interceptor.AuthInterceptor;
import com.silas.iris.toolgateway.common.redis.RedisService;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import com.silas.iris.toolgateway.metrics.constant.MetricsQueryConstants;
import com.silas.iris.toolgateway.metrics.model.dto.QueryRangeDTO;
import com.silas.iris.toolgateway.metrics.model.dto.WindowQueryDTO;
import com.silas.iris.toolgateway.metrics.model.vo.QueryRangeVO;
import com.silas.iris.toolgateway.metrics.utils.MetricsTemplateUtil;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Prometheus 指标查询工具：封装 query_range 接口，返回统一 {@link ApiEnvelope} 信封。
 * <p>
 * 上游 4xx/5xx、网络异常、参数校验失败等非正常路径统一交给
 * {@link com.silas.iris.toolgateway.common.web.GlobalExceptionHandler} 处理，
 * 本 controller 只负责正常路径的编排。
 *
 * @author silas
 * @since 2026/7/31 17:25
 */
@Slf4j
@RestController
@RequestMapping("/metrics")
@Validated
@Tag(name = "Metrics 查询", description = "封装 Prometheus query_range 接口的指标查询工具")
public class MetricsController {

    private final RedisService redisService;
    private final MetricsTemplateUtil metricsTemplateUtil;
    @Value("${iris.prometheus.base-url}")
    private String prometheusBaseUrl;

    @Value("${iris.prometheus.connect-timeout-ms:3000}")
    private int prometheusConnectTimeoutMs;

    @Value("${iris.prometheus.read-timeout-ms:10000}")
    private int prometheusReadTimeoutMs;

    private RestClient prometheusClient;

    public MetricsController(RedisService redisService, MetricsTemplateUtil metricsTemplateUtil) {
        this.redisService = redisService;
        this.metricsTemplateUtil = metricsTemplateUtil;
    }

    /**
     * 初始化 Prometheus HTTP 客户端，在 bean 构造完成、依赖注入结束后由 Spring 自动调用一次。
     */
    @PostConstruct
    void initPrometheusClient() {
        // 上游 Prometheus 挂起时不能无限期占用线程，必须显式设置连接/读超时；
        // 否则即便 GlobalExceptionHandler 里配了 503/网络异常走降级，这个调用也永远等不到异常抛出。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(prometheusConnectTimeoutMs);
        requestFactory.setReadTimeout(prometheusReadTimeoutMs);
        this.prometheusClient = RestClient.builder()
                .baseUrl(prometheusBaseUrl)
                .requestFactory(requestFactory)
                .build();
        log.info("Prometheus 客户端初始化完成，connectTimeoutMs: {}, readTimeoutMs: {}",
                prometheusConnectTimeoutMs, prometheusReadTimeoutMs);
    }

    /**
     * 查询指定时间窗口内的时序指标数据。
     * <p>
     * 编排逻辑：消费本次 incident 的工具调用配额 -&gt; 解析采样 step -&gt; 渲染 PromQL ->
     * 推算查询时间范围 -&gt; 组装并请求 Prometheus query_range 接口 -&gt; 封装统一响应信封。
     * 具体计算细节下沉到各私有方法，本方法只保留编排顺序。
     *
     * @param windowQueryDTO 查询参数：模板 key、目标服务、时间窗口（秒）
     * @param incidentId     当前事件 ID，由 {@link AuthInterceptor} 从请求头解析后放入 request attribute
     * @param request        当前 HTTP 请求，用于写入审计上下文属性
     * @return 统一响应信封，data 为 Prometheus query_range 原始响应
     */
    @PostMapping("/query")
    @Operation(summary = "查询时序指标", description = "透传 PromQL 表达式查询 Prometheus query_range 接口，返回统一响应信封")
    @Parameters({
            @Parameter(
                    name = HeaderConstants.AUTHORIZATION_HEADER,
                    description = "访问令牌，格式：Bearer <token>",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "Bearer your-token"
            ),
            @Parameter(
                    name = HeaderConstants.INCIDENT_ID_HEADER,
                    description = "事件 ID",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "INC-20260731-001"
            ),
            @Parameter(
                    name = HeaderConstants.AGENT_ROLE_HEADER,
                    description = "Agent 角色",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "investigate"
            )
    })
    public ApiEnvelope<QueryRangeVO> query(
            @Valid @RequestBody WindowQueryDTO windowQueryDTO,
            @RequestAttribute(HeaderConstants.INCIDENT_ID_ATTR) String incidentId,
            HttpServletRequest request) {

        long startTask = System.currentTimeMillis();
        request.setAttribute(HeaderConstants.TOOL_NAME_ATTR, AuditConstants.QUERY_METRICS);
        request.setAttribute(HeaderConstants.TEMPLATE_OR_RAW_ATTR, AuditConstants.TEMPLATE);
        request.setAttribute(HeaderConstants.AUDIT_REQUEST_PARAMS_ATTR, BeanUtil.beanToMap(windowQueryDTO));
        log.info("开始指标查询，incidentId: {}, templateKey: {}, service: {}, window: {}",
                incidentId, windowQueryDTO.getTemplateKey(), windowQueryDTO.getService(), windowQueryDTO.getWindow());

        // 1. 校验 -> 限流：消费本次 incident 的工具调用配额，超限直接抛出交给 GlobalExceptionHandler
        Long remaining = redisService.consume_tool_calls(incidentId);

        // 2. 分发：解析 step、渲染 PromQL、推算时间范围、组装请求参数，并请求 Prometheus
        int window = windowQueryDTO.getWindow();
        String promQL = metricsTemplateUtil.render(windowQueryDTO.getTemplateKey(), windowQueryDTO.getService(), StrUtil.toString(window));
        TimeRange timeRange = resolveTimeRange(window, windowQueryDTO.getEndOffsetSeconds());
        Map<String, Object> queryRangeParams = buildQueryRangeParams(promQL, timeRange, resolveStep(window));
        log.info("开始请求 Prometheus query_range，incidentId: {}", incidentId);
        QueryRangeVO data = fetchQueryRange(queryRangeParams);

        // 3. 组装结果：统一信封 + meta
        log.info("指标查询完成，incidentId: {}, remaining: {}, elapsedMs: {}",
                incidentId, remaining, System.currentTimeMillis() - startTask);
        return ApiEnvelope.ok(data, buildMeta(startTask, remaining));
    }

    /**
     * PromQL 逃生通道：跳过模板渲染，直接使用调用方提供的 PromQL 查询 Prometheus query_range 接口。
     * 用于模板覆盖不到的诊断需求。
     * <p>
     * 编排顺序：先做本地 label matcher 强校验（零网络/零预算成本）-&gt; 校验通过才消费本次 incident
     * 的 raw 通道工具调用配额（与模板通道使用独立的 key/限额，见 {@link RedisService#consume_raw_tool_calls}，
     * 互不挤占对方额度，格式错误的裸查询不会消耗任何一侧的预算）
     * -&gt; 其余流程（时间范围推算、step 反推、请求 Prometheus、封装信封）与模板通道完全一致，直接复用。
     *
     * @param windowQueryDTO 查询参数：裸 PromQL（query）、目标服务、时间窗口（秒）
     * @param incidentId     当前事件 ID，由 {@link AuthInterceptor} 从请求头解析后放入 request attribute
     * @param request        当前 HTTP 请求，用于写入审计上下文属性
     * @return 统一响应信封，data 为 Prometheus query_range 原始响应
     */
    @PostMapping("/query/raw")
    @Operation(summary = "裸 PromQL 查询（raw 逃生通道）",
            description = "跳过模板渲染，直接透传调用方提供的 PromQL 查询 Prometheus query_range 接口；" +
                    "查询必须包含至少一个 label matcher，否则 403 拒绝")
    @Parameters({
            @Parameter(
                    name = HeaderConstants.AUTHORIZATION_HEADER,
                    description = "访问令牌，格式：Bearer <token>",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "Bearer your-token"
            ),
            @Parameter(
                    name = HeaderConstants.INCIDENT_ID_HEADER,
                    description = "事件 ID",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "INC-20260731-001"
            ),
            @Parameter(
                    name = HeaderConstants.AGENT_ROLE_HEADER,
                    description = "Agent 角色",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "investigate"
            )
    })
    public ApiEnvelope<QueryRangeVO> queryRaw(
            @Valid @RequestBody WindowQueryDTO windowQueryDTO,
            @RequestAttribute(HeaderConstants.INCIDENT_ID_ATTR) String incidentId,
            HttpServletRequest request) {

        long startTask = System.currentTimeMillis();
        request.setAttribute(HeaderConstants.TOOL_NAME_ATTR, AuditConstants.QUERY_METRICS);
        request.setAttribute(HeaderConstants.TEMPLATE_OR_RAW_ATTR, AuditConstants.RAW);
        request.setAttribute(HeaderConstants.AUDIT_REQUEST_PARAMS_ATTR, BeanUtil.beanToMap(windowQueryDTO));
        String promQL = windowQueryDTO.getQuery();
        log.info("开始裸 PromQL 查询，incidentId: {}, service: {}, window: {}",
                incidentId, windowQueryDTO.getService(), windowQueryDTO.getWindow());

        // 1. 本地强校验：必须带 label matcher，不满足直接 403，不消费预算
        validateRawPromQL(promQL);

        // 2. 校验通过 -> 限流：消费本次 incident 的 raw 通道工具调用配额（与模板通道独立计数）
        Long remaining = redisService.consume_raw_tool_calls(incidentId);

        // 3. 分发：推算时间范围、组装请求参数，并请求 Prometheus（不做模板渲染，promQL 即调用方原样传入的查询）
        int window = windowQueryDTO.getWindow();
        TimeRange timeRange = resolveTimeRange(window, windowQueryDTO.getEndOffsetSeconds());
        Map<String, Object> queryRangeParams = buildQueryRangeParams(promQL, timeRange, resolveStep(window));
        log.info("开始请求 Prometheus query_range（raw），incidentId: {}", incidentId);
        QueryRangeVO data = fetchQueryRange(queryRangeParams);

        // 4. 组装结果：统一信封 + meta
        log.info("裸 PromQL 查询完成，incidentId: {}, remaining: {}, elapsedMs: {}",
                incidentId, remaining, System.currentTimeMillis() - startTask);
        return ApiEnvelope.ok(data, buildMeta(startTask, remaining));
    }

    /**
     * raw 通道的 PromQL label matcher 强校验：要求查询串中任意位置至少出现一个
     * {@code {label="value"}}/{@code label!="value"}/{@code label=~"regex"}/{@code label!~"regex"} 形式的匹配，
     * 防止未加范围限制的全量查询打到 Prometheus。注意不能用 {@code startWith} 之类的前缀判断——
     * PromQL 的 label matcher 通常跟在指标名之后（如 {@code rate(x{service="a"}[5m])}），不在字符串开头，
     * 这一点与 LogQL "必须以 label selector 开头" 的惯例不同。
     * <p>
     * 仅做语法层面的范围校验，不做语义/权限校验（例如不检查 label 的值是否为调用方有权访问的 service，
     * 这依赖 query_cmdb 落地后的 ServiceRegistry，不在本方法范围内）。
     *
     * @param query 调用方传入的裸 PromQL
     * @throws RawQueryRejectedException 未找到任何 label matcher
     */
    private void validateRawPromQL(String query) {
        if (!MetricsQueryConstants.RAW_PROMQL_LABEL_MATCHER.matcher(query).find()) {
            throw new RawQueryRejectedException("PromQL 查询必须包含至少一个 label matcher，如 {service=\"xxx\"}");
        }
    }

    /**
     * 计算 Prometheus query_range 的采样 step：按窗口大小与最大采样点数反推，
     * 但不低于目标服务的原生 scrape_interval，避免 step 小于采集间隔导致空洞或重复采样。
     *
     * @param window 查询时间窗口，单位秒
     * @return 采样步长，单位秒
     */
    private int resolveStep(int window) {
        int requestStep = NumberUtil.ceilDiv(window, MetricsQueryConstants.MAX_SAMPLE_POINTS);
        return Math.max(requestStep, MetricsQueryConstants.DEFAULT_NATIVE_SCRAPE_INTERVAL_SECONDS);
    }

    /**
     * 根据窗口大小推算 query_range 的查询时间范围：以当前时刻回溯 endOffsetSeconds 为 end，
     * 再从 end 向前回溯 window 秒为 start。endOffsetSeconds 为空时默认 0，即 end = now，
     * 与此前"只能查最近 window 秒"的行为保持兼容。
     * <p>
     * 引入 endOffsetSeconds 是为了让 window 可以平移到过去任意位置：粗粒度总览定位到可疑区间后，
     * 精查时只需缩小 window 并同时传入 endOffsetSeconds 把查询窗口移到该区间，而不必因为
     * end 恒为 now 被迫要么放大 window 覆盖到该区间（step 被拉粗），要么缩小 window 却把
     * 该区间挤出查询范围之外。
     *
     * @param window           查询时间窗口，单位秒
     * @param endOffsetSeconds 查询结束时间相对当前时刻的回溯偏移量，单位秒，可为 null
     * @return 本次查询的起止时间
     */
    private TimeRange resolveTimeRange(int window, Long endOffsetSeconds) {
        Instant end = Instant.now().minusSeconds(Objects.requireNonNullElse(endOffsetSeconds, 0L));
        Instant start = end.minusSeconds(window);
        return new TimeRange(start, end);
    }

    /**
     * 组装 Prometheus query_range 接口所需的请求参数。
     *
     * @param promQL    渲染完成的 PromQL 表达式
     * @param timeRange 查询起止时间
     * @param step      采样步长，单位秒
     * @return 请求参数键值对，null 值字段已被忽略，可直接作为 query string 使用
     */
    private Map<String, Object> buildQueryRangeParams(String promQL, TimeRange timeRange, int step) {
        QueryRangeDTO queryRangeDTO = QueryRangeDTO.builder()
                .query(promQL)
                .start(timeRange.start().toString())
                .end(timeRange.end().toString())
                .step(step + "s")
                .limit(MetricsQueryConstants.RESULT_SERIES_LIMIT)
                .build();
        // 将查询对象转换成 map，忽略 null 值，作为 query_range 的请求参数
        return BeanUtil.beanToMap(queryRangeDTO, new HashMap<>(), true, true);
    }

    /**
     * 请求 Prometheus query_range 接口。上游 4xx/5xx 或网络异常会直接抛出，
     * 交给 {@link com.silas.iris.toolgateway.common.web.GlobalExceptionHandler} 统一处理。
     *
     * @param queryRangeParams 请求参数键值对
     * @return Prometheus query_range 原始响应
     */
    private QueryRangeVO fetchQueryRange(Map<String, Object> queryRangeParams) {
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromPath(MetricsQueryConstants.QUERY_RANGE_PATH);
        queryRangeParams.forEach(uriBuilder::queryParam);
        URI uri = uriBuilder.build().encode().toUri();
        return prometheusClient.get()
                .uri(uri)
                .retrieve()
                .body(QueryRangeVO.class);
    }

    /**
     * 组装响应信封的 meta 信息：耗时、截断标记（当前查询链路暂不做截断，固定为 false）、剩余调用预算。
     *
     * @param startTask 本次请求开始时间戳（毫秒）
     * @param remaining 本次 incident 的剩余调用次数
     * @return meta 信息
     */
    private ApiEnvelope.Meta buildMeta(long startTask, long remaining) {
        return ApiEnvelope.Meta.builder()
                .elapsedMs(System.currentTimeMillis() - startTask)
                .truncated(false)
                .toolCallsRemaining(remaining)
                .build();
    }

    /**
     * 一次 query_range 查询的时间范围，start/end 均为闭区间端点。
     */
    private record TimeRange(Instant start, Instant end) {
    }
}
