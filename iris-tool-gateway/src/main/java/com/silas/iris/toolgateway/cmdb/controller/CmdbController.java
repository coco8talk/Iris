package com.silas.iris.toolgateway.cmdb.controller;

import com.silas.iris.toolgateway.cmdb.model.dto.CmdbQueryDTO;
import com.silas.iris.toolgateway.cmdb.model.vo.ServiceTopologyVO;
import com.silas.iris.toolgateway.cmdb.service.ServiceRegistry;
import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import com.silas.iris.toolgateway.common.interceptor.AuthInterceptor;
import com.silas.iris.toolgateway.common.redis.RedisService;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CMDB 查询工具：从 Nacos 查询指定服务的实例拓扑，返回统一 {@link ApiEnvelope} 信封。
 * 上游异常直接交给全局异常处理器，本 controller 只负责正常路径的编排。
 *
 * @author silas
 * @since 2026/8/2
 */
@Slf4j
@RestController
@RequestMapping("/cmdb")
@Validated
@Tag(name = "CMDB 查询", description = "基于 Nacos 服务目录查询服务实例拓扑")
public class CmdbController {

    private final RedisService redisService;
    private final ServiceRegistry serviceRegistry;

    public CmdbController(RedisService redisService, ServiceRegistry serviceRegistry) {
        this.redisService = redisService;
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * 查询指定服务在 Nacos 中登记的实例拓扑。
     *
     * @param cmdbQueryDTO 查询参数：目标服务
     * @param incidentId  当前事件 ID，由 {@link AuthInterceptor} 写入 request attribute
     * @return 统一响应信封，data 为服务实例拓扑
     */
    @PostMapping("/query")
    @Operation(summary = "查询服务拓扑", description = "查询指定服务在 Nacos 中登记的实例拓扑")
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
                    example = "investigator"
            )
    })
    public ApiEnvelope<ServiceTopologyVO> query(
            @Valid @RequestBody CmdbQueryDTO cmdbQueryDTO,
            @RequestAttribute(HeaderConstants.INCIDENT_ID_ATTR) String incidentId) {

        long startTask = System.currentTimeMillis();
        log.info("开始 CMDB 服务拓扑查询，incidentId: {}, service: {}",
                incidentId, cmdbQueryDTO.getService());

        Long remaining = redisService.consume_tool_calls(incidentId);

        log.info("开始请求 Nacos 服务拓扑，incidentId: {}, service: {}",
                incidentId, cmdbQueryDTO.getService());
        ServiceTopologyVO data = serviceRegistry.queryTopology(cmdbQueryDTO.getService());

        log.info("CMDB 服务拓扑查询完成，incidentId: {}, remaining: {}, elapsedMs: {}",
                incidentId, remaining, System.currentTimeMillis() - startTask);
        return ApiEnvelope.ok(data, buildMeta(startTask, remaining));
    }

    /**
     * 组装响应信封的 meta 信息。
     */
    private ApiEnvelope.Meta buildMeta(long startTask, long remaining) {
        return ApiEnvelope.Meta.builder()
                .elapsedMs(System.currentTimeMillis() - startTask)
                .truncated(false)
                .toolCallsRemaining(remaining)
                .build();
    }
}
