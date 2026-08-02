package com.silas.iris.toolgateway.trace.model.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 从 Tempo OTLP 响应中拍平的 span，仅供 trace 模块内部组树使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpanRaw {

    private String traceId;
    private String spanId;
    private String parentId;
    private String service;
    private String operationName;
    private long startTimeUnixNano;
    private long endTimeUnixNano;
    private long durationMs;
}
