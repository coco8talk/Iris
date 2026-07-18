package com.iris.gateway.changes.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.iris.gateway.audit.model.dto.AuditLogDTO;
import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.audit.service.AuditLogService;
import com.iris.gateway.changes.model.dto.InternalChangeDTO;
import com.iris.gateway.changes.model.entity.ChangeEvent;
import com.iris.gateway.changes.service.ChangeEventService;
import com.iris.gateway.common.result.ApiEnvelope;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 内部变更写入接口：供 injector 写入伪装变更。
 *
 * <p>路径 /internal/changes 由 InternalAuthInterceptor 仅校验 Bearer token（不需要
 * incident 头）；审计以 endpoint=/internal/changes 自然区分于工具通道调用(T17)。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
@Tag(name = "内部变更写入", description = "供 injector 写入伪装变更（Bearer only）")
@RestController
@Validated
public class InternalChangesController {

    private final ChangeEventService changeEventService;
    private final AuditLogService auditLogService;

    public InternalChangesController(ChangeEventService changeEventService, AuditLogService auditLogService) {
        this.changeEventService = changeEventService;
        this.auditLogService = auditLogService;
    }

    /** 本接口路径，同时作为审计日志的 endpoint 字段值（internal 标记） */
    private static final String ENDPOINT = "/internal/changes";

    /**
     * 写入一条变更事件：change_id / ts 缺省时分别生成 UUID / 当前时刻。
     *
     * @param dto     变更写入参数
     * @param request 用于读取 InternalAuthInterceptor 写入的 authContext
     * @return 统一响应信封，data.change_id 为写入的变更标识
     */
    @Operation(summary = "写入内部变更", description = "仅需 Bearer token，无需 incident 头")
    @PostMapping(ENDPOINT)
    public ApiEnvelope<Object> writeChange(
            @Parameter(description = "变更写入参数", required = true)
            @RequestBody @Valid InternalChangeDTO dto,
            HttpServletRequest request) {

        long started = System.currentTimeMillis();
        long resultBytes = 0;
        int status = 200;

        try {
            ChangeEvent event = new ChangeEvent();
            event.setChangeId(StrUtil.isBlank(dto.changeId())
                    ? "chg-" + UUID.randomUUID() : dto.changeId());
            event.setTs(StrUtil.isBlank(dto.ts()) ? Instant.now().toString() : dto.ts());
            event.setType(dto.type());
            event.setService(dto.service());
            event.setSummary(dto.summary());
            event.setOperator(dto.operator());
            changeEventService.save(event);

            Object data = Map.of("change_id", event.getChangeId());
            resultBytes = JSONUtil.toJsonStr(data).getBytes(StandardCharsets.UTF_8).length;
            return ApiEnvelope.ok(data, System.currentTimeMillis() - started);
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
                            .templateOrRaw("internal")
                            .paramsJson(JSONUtil.toJsonStr(dto))
                            .upstreamMs(System.currentTimeMillis() - started)
                            .status(status)
                            .resultBytes(resultBytes)
                            .degraded(false)
                            .build()
            );
        }
    }
}
