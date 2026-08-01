package com.silas.iris.toolgateway.matrics.controller;

import cn.hutool.core.bean.BeanUtil;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import com.silas.iris.toolgateway.matrics.model.dto.QueryRangeDTO;
import com.silas.iris.toolgateway.matrics.model.vo.QueryRangeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

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
@RestController
@RequestMapping("/matrics")
@Validated
@Tag(name = "Metrics 查询", description = "封装 Prometheus query_range 接口的指标查询工具")
public class MatricsController {

    RestClient prometheusClient = RestClient.create("http://localhost:9090");

    /**
     * 查询指定时间窗口内的时序指标数据。
     */
    @PostMapping("/query")
    @Operation(summary = "查询时序指标", description = "透传 PromQL 表达式查询 Prometheus query_range 接口，返回统一响应信封")
    @Parameters({
            @Parameter(
                    name = "Authorization",
                    description = "访问令牌，格式：Bearer <token>",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "Bearer your-token"
            ),
            @Parameter(
                    name = "X-Incident-Id",
                    description = "事件 ID",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "INC-20260731-001"
            ),
            @Parameter(
                    name = "X-Agent-Role",
                    description = "Agent 角色",
                    in = ParameterIn.HEADER,
                    required = true,
                    example = "investigator"
            )
    })
    public ApiEnvelope<QueryRangeVO> query(@Valid @RequestBody QueryRangeDTO queryRangeDTO) {

        long start = System.currentTimeMillis();

        // 1. 将查询对象转换成 map，忽略 null 值，作为 query_range 的请求参数
        Map<String, Object> queryRangeMap = BeanUtil.beanToMap(
                queryRangeDTO,
                new HashMap<>(),
                true,
                true);

        // 2. 请求 Prometheus 接口；上游 4xx/5xx 或网络异常会直接抛出，交给 GlobalExceptionHandler 统一处理
        QueryRangeVO data = prometheusClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/v1/query_range");
                    queryRangeMap.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .retrieve()
                .body(QueryRangeVO.class);

        // 3. 包装统一信封返回
        // todo truncated/budgetRemaining 待降采样、BudgetService 接入后补真实值
        ApiEnvelope.Meta meta = ApiEnvelope.Meta.builder()
                .elapsedMs(System.currentTimeMillis() - start)
                .truncated(false)
                .budgetRemaining(0)
                .build();

        return ApiEnvelope.ok(data, meta);

        // todo 审计
    }
}
