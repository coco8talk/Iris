package com.iris.gateway.changes.controller;

import cn.hutool.json.JSONUtil;
import com.iris.gateway.audit.model.dto.AuditLogDTO;
import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.audit.service.AuditLogService;
import com.iris.gateway.changes.model.dto.ChangesQuery;
import com.iris.gateway.changes.service.ChangeEventService;
import com.iris.gateway.changes.vo.ChangeVO;
import com.iris.gateway.common.exception.ApiException;
import com.iris.gateway.common.result.ApiEnvelope;
import com.iris.gateway.common.util.Windows;
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
import java.util.List;
import java.util.Map;

/**
 * 变更事件查询接口：按时间窗（可选服务过滤）返回变更列表，
 * 每次调用无论成败均写入审计日志(T17)。
 *
 * @author silas
 * @since 2026/7/18
 */
@Tag(name = "变更查询", description = "按时间窗查询发版/配置/重启等变更事件")
@RestController
@Validated
public class ChangesController {

    private final ChangeEventService changeEventService;
    private final AuditLogService auditLogService;

    public ChangesController(ChangeEventService changeEventService, AuditLogService auditLogService) {
        this.changeEventService = changeEventService;
        this.auditLogService = auditLogService;
    }

    /** 本接口路径，同时作为审计日志的 endpoint 字段值 */
    private static final String ENDPOINT = "/api/v1/tools/query_changes";

    /**
     * 变更查询入口：解析时间窗、查询变更、finally 写审计。
     *
     * @param query   查询参数，window 必填
     * @param request 用于读取拦截器写入的 authContext
     * @return 统一响应信封，data.changes 为变更列表
     */
    @Operation(summary = "查询变更事件",
            description = "按 window(15m|30m|1h|6h) 查询变更，可选 service 过滤，按时间倒序返回")
    @PostMapping(ENDPOINT)
    public ApiEnvelope<Object> queryChanges(
            @Parameter(description = "变更查询参数", required = true)
            @RequestBody @Valid ChangesQuery query,
            HttpServletRequest request) {

        long started = System.currentTimeMillis();
        long resultBytes = 0;
        int status = 200;

        try {
            Duration window = Windows.required(query.window());
            List<ChangeVO> changes = changeEventService.query(window, query.service());
            Object data = Map.of("changes", changes);
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
                            .templateOrRaw(query.window())
                            .paramsJson(JSONUtil.toJsonStr(query))
                            .upstreamMs(System.currentTimeMillis() - started)
                            .status(status)
                            .resultBytes(resultBytes)
                            .degraded(false)
                            .build()
            );
        }
    }
}
