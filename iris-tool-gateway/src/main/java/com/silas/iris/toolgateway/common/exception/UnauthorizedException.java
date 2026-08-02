package com.silas.iris.toolgateway.common.exception;

/**
 * Authorization 缺失、格式不对或 token 不匹配。必须 401，不能落进兜底 500。
 *
 * @author silas
 * @since 2026/8/1
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
