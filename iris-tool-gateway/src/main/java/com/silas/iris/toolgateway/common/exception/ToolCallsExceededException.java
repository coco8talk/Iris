package com.silas.iris.toolgateway.common.exception;

/**
 * 方法调用次数超出限制
 *
 * @author silas
 * @since 2026/8/1
 */
public class ToolCallsExceededException extends RuntimeException {

    public ToolCallsExceededException(String incidentId) {
        super(incidentId);
    }
}
