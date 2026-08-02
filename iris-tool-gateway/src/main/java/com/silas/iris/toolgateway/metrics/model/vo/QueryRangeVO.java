package com.silas.iris.toolgateway.metrics.model.vo;

import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Prometheus query_range 接口的原始响应结构，用于 PrometheusClient 反序列化上游返回的 JSON。
 * 这是上游原样格式，尚未做降采样/信封包装；PrometheusClient 需要基于此再加工成
 * {@link ApiEnvelope} 返回给调用方。
 *
 * @author silas
 * @since 2026/7/31 17:25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Prometheus query_range 原始响应")
public class QueryRangeVO {

    @Schema(description = "查询状态，success 或 error", example = "success")
    @NotBlank(message = "status 不能为空")
    private String status;

    @Schema(description = "查询结果数据，status=success 时有值")
    @Valid
    private RangeData data;

    @Schema(description = "错误类型，仅 status=error 时有值", example = "bad_data")
    private String errorType;

    @Schema(description = "错误信息，仅 status=error 时有值")
    private String error;

    @Schema(description = "执行过程中产生的告警信息，仅在有告警时有值；此时 data 仍然有数据")
    private List<String> warnings;

    @Schema(description = "执行过程中产生的 info 级别提示信息，仅在有提示时有值")
    private List<String> infos;

    /**
     * data 字段的内容：结果类型 + 按时间序列分组的结果列表。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "查询结果数据体")
    public static class RangeData {

        @Schema(description = "结果类型，range_query 场景固定为 matrix", example = "matrix")
        @NotBlank(message = "resultType 不能为空")
        private String resultType;

        @Schema(description = "按时间序列分组的查询结果列表")
        @Valid
        @NotEmpty(message = "result 不能为空")
        private List<SeriesResult> result;
    }

    /**
     * 单条时间序列的结果：标签集合 + 采样点列表。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "单条时间序列结果")
    public static class SeriesResult {

        @Schema(description = "该时间序列的标签集合，如 __name__/job/instance")
        @NotEmpty(message = "metric 不能为空")
        private Map<String, String> metric;

        @Schema(description = "该时间序列的采样点列表，每个元素是 [时间戳(秒，可带小数), 值(字符串)] 二元组")
        @NotEmpty(message = "values 不能为空")
        private List<List<Object>> values;
    }
}
