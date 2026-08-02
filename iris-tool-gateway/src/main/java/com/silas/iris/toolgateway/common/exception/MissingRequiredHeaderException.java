package com.silas.iris.toolgateway.common.exception;

/**
 * 缺少必需的请求头（X-Incident-Id / X-Agent-Role）。必须 400，不能落进兜底 500。
 *
 * @author silas
 * @since 2026/8/1
 */
public class MissingRequiredHeaderException extends RuntimeException {

    public MissingRequiredHeaderException(String message) {
        super(message);
    }
}
