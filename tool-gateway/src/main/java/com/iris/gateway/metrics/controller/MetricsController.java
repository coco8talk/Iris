package com.iris.gateway.metrics.controller;

import cn.hutool.json.JSONUtil;
import com.iris.gateway.audit.model.dto.AuditLogDTO;
import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.audit.service.AuditLogService;
import com.iris.gateway.cmdb.service.CmdbServiceService;
import com.iris.gateway.common.exception.ApiException;
import com.iris.gateway.common.exception.UpstreamUnavailableException;
import com.iris.gateway.common.result.ApiEnvelope;
import com.iris.gateway.common.util.Windows;
import com.iris.gateway.metrics.AnomalyHint;
import com.iris.gateway.metrics.Downsampler;
import com.iris.gateway.metrics.MetricsTemplateRegistry;
import com.iris.gateway.metrics.MetricsTemplateRegistry.Template;
import com.iris.gateway.metrics.Point;
import com.iris.gateway.metrics.PrometheusClient;
import com.iris.gateway.metrics.QueryResult;
import com.iris.gateway.metrics.model.dto.MetricsQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标查询接口：模板化 PromQL → 统计摘要 + ≤30 点降采样 + 规则化异常提示(T14)。
 *
 * <p>Prometheus 5s 超时/不可用按 H3 返回 200 + degraded:true；window>6h 返回 400 RANGE_TOO_LARGE。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
@Tag(name = "指标查询", description = "12 模板指标查询：统计摘要 + 降采样点 + 异常提示")
@RestController
@Validated
public class MetricsController {

    /** 降采样点数上限（§0.3：每 series ≤30 点） */
    private static final int MAX_POINTS = 30;

    /** series 截断阈值（§0.3：≤20 series） */
    private static final int MAX_SERIES = 20;

    /** 基线回溯间隔：24h 前同窗口 */
    private static final Duration BASELINE_SHIFT = Duration.ofHours(24);

    private final CmdbServiceService cmdbServiceService;
    private final PrometheusClient prometheusClient;
    private final AuditLogService auditLogService;

    public MetricsController(CmdbServiceService cmdbServiceService,
                             PrometheusClient prometheusClient,
                             AuditLogService auditLogService) {
        this.cmdbServiceService = cmdbServiceService;
        this.prometheusClient = prometheusClient;
        this.auditLogService = auditLogService;
    }

    /** 本接口路径，同时作为审计日志的 endpoint 字段值 */
    private static final String ENDPOINT = "/api/v1/tools/query_metrics";

    @Operation(summary = "查询指标",
            description = "按 template(12 选一) 查询 service 的指标统计摘要与降采样点，可选对比 24h 前基线")
    @PostMapping(ENDPOINT)
    public ApiEnvelope<Object> queryMetrics(
            @Parameter(description = "指标查询参数", required = true)
            @RequestBody @Valid MetricsQuery query,
            HttpServletRequest request) {

        long started = System.currentTimeMillis();
        long resultBytes = 0;
        int status = 200;
        boolean degraded = false;

        try {
            Template template = MetricsTemplateRegistry.require(query.template());
            if (cmdbServiceService.getVoByName(query.service()) == null) {
                throw ApiException.invalidParam("unknown service: " + query.service());
            }
            String windowStr = query.window() == null ? "30m" : query.window();
            Duration window = Windows.ceiling6h(windowStr);

            Instant end = Instant.now();
            Instant start = end.minus(window);
            Duration step = stepFor(window);
            String promql = MetricsTemplateRegistry.promql(template, query.service());

            QueryResult current;
            try {
                current = prometheusClient.queryRange(promql, start, end, step);
            } catch (UpstreamUnavailableException e) {
                degraded = true;
                return ApiEnvelope.degraded(e.getMessage(), System.currentTimeMillis() - started);
            }

            List<Point> points = Downsampler.downsample(current.points(), MAX_POINTS);

            Map<String, Object> baselineStats = null;
            Double baselineAvg = null;
            if (Boolean.TRUE.equals(query.compareBaseline())) {
                try {
                    QueryResult base = prometheusClient.queryRange(
                            promql, start.minus(BASELINE_SHIFT), end.minus(BASELINE_SHIFT), step);
                    List<Point> basePoints = Downsampler.downsample(base.points(), MAX_POINTS);
                    baselineStats = stats(basePoints);
                    baselineAvg = basePoints.isEmpty() ? null : avg(basePoints);
                } catch (UpstreamUnavailableException e) {
                    // 基线拉取失败不整体降级，仅缺 baseline
                    baselineStats = null;
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("template", template.id());
            data.put("unit", template.unit());
            data.put("stats", stats(points));
            data.put("anomaly_hint", points.isEmpty() ? null : AnomalyHint.of(last(points), baselineAvg));
            data.put("points", toPointArray(points));
            if (baselineStats != null) {
                data.put("baseline_stats", baselineStats);
            }

            boolean truncated = current.seriesCount() > MAX_SERIES;
            resultBytes = JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8).length;
            return ApiEnvelope.ok(data, System.currentTimeMillis() - started, truncated);
        } catch (ApiException e) {
            status = e.status().value();
            throw e;
        } catch (Exception e) {
            status = 500;
            throw e;
        } finally {
            AuthContextDTO authContext = (AuthContextDTO) request.getAttribute("authContext");
            auditLogService.record(
                    AuditLogDTO.builder()
                            .incidentId(authContext.getIncidentId())
                            .agentRole(authContext.getAgentRole())
                            .endpoint(ENDPOINT)
                            .templateOrRaw(query.template())
                            .paramsJson(JSONUtil.toJsonStr(query))
                            .upstreamMs(System.currentTimeMillis() - started)
                            .status(status)
                            .resultBytes(resultBytes)
                            .degraded(degraded)
                            .build()
            );
        }
    }

    /** 步长自适应：使 Prometheus 返回点数 ≈ ≤30，最小 15s */
    private Duration stepFor(Duration window) {
        long seconds = Math.max(window.getSeconds() / MAX_POINTS, 15);
        return Duration.ofSeconds(seconds);
    }

    /** 统计摘要；空点集时各值为 null */
    private Map<String, Object> stats(List<Point> points) {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (points.isEmpty()) {
            stats.put("min", null);
            stats.put("max", null);
            stats.put("avg", null);
            stats.put("last", null);
            return stats;
        }
        double min = points.stream().mapToDouble(Point::value).min().orElse(0);
        double max = points.stream().mapToDouble(Point::value).max().orElse(0);
        stats.put("min", min);
        stats.put("max", max);
        stats.put("avg", avg(points));
        stats.put("last", last(points));
        return stats;
    }

    private double avg(List<Point> points) {
        return points.stream().mapToDouble(Point::value).average().orElse(0);
    }

    private double last(List<Point> points) {
        return points.get(points.size() - 1).value();
    }

    /** 转为 [[iso_ts, value], ...] */
    private List<List<Object>> toPointArray(List<Point> points) {
        List<List<Object>> out = new ArrayList<>(points.size());
        for (Point p : points) {
            out.add(List.of(p.ts().toString(), p.value()));
        }
        return out;
    }
}
