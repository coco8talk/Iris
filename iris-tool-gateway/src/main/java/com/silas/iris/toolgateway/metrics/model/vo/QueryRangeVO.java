package com.silas.iris.toolgateway.metrics.model.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * Prometheus query_range 查询结果。
 * 反序列化时从上游响应的 data 字段中提取结果，对外不暴露 Prometheus 的响应信封字段。
 *
 * @author silas
 * @since 2026/7/31 17:25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Prometheus query_range 查询结果")
public class QueryRangeVO {

    @Schema(description = "结果类型，range_query 场景固定为 matrix", example = "matrix")
    @NotBlank(message = "resultType 不能为空")
    private String resultType;

    @Schema(description = "按时间序列分组的查询结果列表")
    @Valid
    @NotEmpty(message = "result 不能为空")
    private List<SeriesResult> result;

    @JsonProperty("data")
    private void unpackData(QueryRangeVO data) {
        if (data != null) {
            this.resultType = data.getResultType();
            this.result = data.getResult();
        }
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
