package com.iris.gateway.logs.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.iris.gateway.audit.model.dto.AuditLogDTO;
import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.audit.service.AuditLogService;
import com.iris.gateway.common.exception.ApiException;
import com.iris.gateway.common.exception.UpstreamUnavailableException;
import com.iris.gateway.common.result.ApiEnvelope;
import com.iris.gateway.common.util.Windows;
import com.iris.gateway.logs.LogLine;
import com.iris.gateway.logs.LokiClient;
import com.iris.gateway.logs.LokiClient.RawLog;
import com.iris.gateway.logs.PatternAggregator;
import com.iris.gateway.logs.TraceIdExtractor;
import com.iris.gateway.logs.model.dto.LogsQuery;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志查询接口：LogQL 构造 + 确定性 pattern 聚合 + traceId 提取(T15)。
 *
 * <p>Loki 8s 超时/不可用按 H3 返回 200 + degraded:true；limit>100 返回 400。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
@Tag(name = "日志查询", description = "模板行聚合 + 原始行，traceId 作横向枢纽")
@RestController
@Validated
public class LogsController {

    /** 行数上限（§0.3：logs ≤100 行） */
    private static final int MAX_LIMIT = 100;

    /** 合法日志级别 */
    private static final Set<String> LEVELS = Set.of("ERROR", "WARN");

    /** 日志级别提取：取行内首个级别 token */
    private static final Pattern LEVEL = Pattern.compile("\\b(ERROR|WARN|INFO|DEBUG|TRACE)\\b");

    private final LokiClient lokiClient;
    private final AuditLogService auditLogService;

    public LogsController(LokiClient lokiClient, AuditLogService auditLogService) {
        this.lokiClient = lokiClient;
        this.auditLogService = auditLogService;
    }

    /** 本接口路径，同时作为审计日志的 endpoint 字段值 */
    private static final String ENDPOINT = "/api/v1/tools/query_logs";

    @Operation(summary = "查询日志",
            description = "按 service 查询日志，返回模板聚合 patterns 与原始 lines（≤limit）")
    @PostMapping(ENDPOINT)
    public ApiEnvelope<Object> queryLogs(
            @Parameter(description = "日志查询参数", required = true)
            @RequestBody @Valid LogsQuery query,
            HttpServletRequest request) {

        long started = System.currentTimeMillis();
        long resultBytes = 0;
        int status = 200;
        boolean degraded = false;

        try {
            int limit = query.limit() == null ? 50 : query.limit();
            if (limit < 1 || limit > MAX_LIMIT) {
                throw ApiException.invalidParam("limit must be between 1 and " + MAX_LIMIT);
            }
            if (StrUtil.isNotBlank(query.level()) && !LEVELS.contains(query.level())) {
                throw ApiException.invalidParam("level must be ERROR or WARN");
            }
            Duration window = Windows.required(query.window() == null ? "30m" : query.window());

            Instant end = Instant.now();
            Instant start = end.minus(window);
            String logql = LokiClient.buildLogQL(query.service(), query.level(), query.keyword());

            List<RawLog> raw;
            try {
                raw = lokiClient.queryRange(logql, start, end, limit);
            } catch (UpstreamUnavailableException e) {
                degraded = true;
                return ApiEnvelope.degraded(e.getMessage(), System.currentTimeMillis() - started);
            }

            List<LogLine> lines = new ArrayList<>(raw.size());
            for (RawLog log : raw) {
                lines.add(parse(log));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("patterns", PatternAggregator.aggregate(lines));
            data.put("lines", lines);
            resultBytes = JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8).length;
            return ApiEnvelope.ok(data, System.currentTimeMillis() - started);
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
                            .templateOrRaw(query.service())
                            .paramsJson(JSONUtil.toJsonStr(query))
                            .upstreamMs(System.currentTimeMillis() - started)
                            .status(status)
                            .resultBytes(resultBytes)
                            .degraded(degraded)
                            .build()
            );
        }
    }

    /** 将原始日志行解析为 {ts, level, msg, trace_id} */
    private LogLine parse(RawLog log) {
        String line = log.line();
        Matcher levelMatcher = LEVEL.matcher(line);
        String level = levelMatcher.find() ? levelMatcher.group(1) : null;
        String traceId = TraceIdExtractor.extract(line);
        int sep = line.indexOf(" - ");
        String msg = sep >= 0 ? line.substring(sep + 3) : line;
        return new LogLine(log.ts().toString(), level, msg, traceId);
    }
}
