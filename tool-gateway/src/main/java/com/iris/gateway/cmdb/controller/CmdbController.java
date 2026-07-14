package com.iris.gateway.cmdb.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.iris.gateway.audit.model.dto.AuditLogDTO;
import com.iris.gateway.audit.model.dto.AuthContextDTO;
import com.iris.gateway.audit.service.AuditLogService;
import com.iris.gateway.cmdb.model.dto.CmdbQueryDTO;
import com.iris.gateway.cmdb.service.CmdbServiceService;
import com.iris.gateway.cmdb.vo.CmdbServiceVO;
import com.iris.gateway.common.enums.TemplateEnum;
import com.iris.gateway.common.exception.ApiException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CMDB 查询接口：按模板返回整体服务拓扑或单一服务详情，
 * 每次调用无论成败均写入审计日志
 *
 * @author silas
 * @since 2026/7/14 02:32
 */
@Tag(name = "CMDB 查询", description = "查询 CMDB 服务拓扑与单一服务详情")
@RestController
@Validated
public class CmdbController {

    private final CmdbServiceService cmdbServiceService;
    private final AuditLogService auditLogService;

    public CmdbController(CmdbServiceService cmdbServiceService, AuditLogService auditLogService) {
        this.cmdbServiceService = cmdbServiceService;
        this.auditLogService = auditLogService;
    }

    /** 本接口路径，同时作为审计日志的 endpoint 字段值 */
    private static final String ENDPOINT = "/api/v1/tools/query_cmdb";

    /**
     * CMDB 查询入口：路由到拓扑或服务详情查询，
     * finally 中记录审计日志（含请求参数、耗时、状态码、响应字节数）。
     *
     * @param cmbdDTO 查询参数，template 必填
     * @param request 用于读取拦截器写入的 authContext 认证上下文
     * @return 统一响应信封，data 为拓扑结构或服务记录
     */
    @Operation(summary = "查询 CMDB",
            description = "按 template 查询：get_topology 返回整体服务拓扑（服务列表、依赖关系、中间件），"
                    + "get_service_detail 返回指定服务的完整 CMDB 记录（需传 service_name）")
    @PostMapping(ENDPOINT)
    public ApiEnvelope<Object> queryCmbd(
            @Parameter(description = "CMDB 查询参数", required = true)
            @RequestBody @Valid CmdbQueryDTO cmbdDTO,
            HttpServletRequest request) {

        long started = System.currentTimeMillis();
        long resultBytes = 0;
        int status = 200;

        String template = cmbdDTO.template();
        try {
            // 1. 判断是需要 整个系统拓扑 还是 单一服务详情
            Object data;
            if (StrUtil.equals(template, TemplateEnum.GET_TOPOLOGY.getTemplate())) {
                data = getTopology();
            } else if (StrUtil.equals(template, TemplateEnum.GET_SERVICE_DETAIL.getTemplate())) {
                data = getServiceDetail(cmbdDTO.serviceName());
            } else {
                throw ApiException.invalidParam("template must be get_topology or get_service_detail");
            }
            // 2. 以 JSON 序列化后的字节数统计响应大小
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
                            .templateOrRaw(template)
                            .paramsJson(JSONUtil.toJsonStr(cmbdDTO))
                            .upstreamMs(System.currentTimeMillis() - started)
                            .status(status)
                            .resultBytes(resultBytes)
                            .degraded(false)
                            .build()
            );
        }
    }

    /**
     * 汇总全部服务为拓扑视图。
     *
     * @return 含 services（服务名列表）、depends_on / middleware（服务名 → 对应属性）三个键
     */
    private Map<String, Object> getTopology() {
        List<CmdbServiceVO> services = cmdbServiceService.listAllVo();
        Map<String, Object> dependsOn = new LinkedHashMap<>();
        Map<String, Object> middleware = new LinkedHashMap<>();
        for (CmdbServiceVO service : services) {
            dependsOn.put(service.getName(), service.getRecord().get("depends_on"));
            middleware.put(service.getName(), service.getRecord().get("middleware"));
        }
        return Map.of(
                "services", services.stream().map(CmdbServiceVO::getName).toList(),
                "depends_on", dependsOn,
                "middleware", middleware);
    }

    /**
     * 查询单一服务的完整 CMDB 记录。
     *
     * @param service 服务名，为空或不存在时抛 400 参数异常
     * @return cmdb.yaml 中该服务的完整记录
     */
    private Map<String, Object> getServiceDetail(String service) {
        if (service == null || service.isBlank()) {
            throw ApiException.invalidParam("service is required for get_service_detail");
        }
        CmdbServiceVO vo = cmdbServiceService.getVoByName(service);
        if (vo == null) {
            throw ApiException.invalidParam("unknown service: " + service);
        }
        return vo.getRecord();
    }
}
