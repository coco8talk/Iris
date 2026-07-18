package com.iris.gateway.common.exception;

import org.springframework.http.HttpStatus;

/**
 * API 业务异常。
 *
 * <p>用于封装接口处理过程中可预期的异常信息，包括 HTTP 状态码、
 * 业务错误码以及面向调用方的错误描述。</p>
 *
 * <p>该异常通常由全局异常处理器捕获，并转换为统一的 API 错误响应。</p>
 */
public class ApiException extends RuntimeException {

    /**
     * 响应使用的 HTTP 状态码。
     */
    private final HttpStatus status;

    /**
     * 业务错误码。
     */
    private final String code;

    /**
     * 创建 API 业务异常。
     *
     * @param status  HTTP 状态码
     * @param code    业务错误码
     * @param message 错误描述
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 创建请求参数不合法异常。
     *
     * @param message 参数校验失败的具体原因
     * @return HTTP 状态码为 {@link HttpStatus#BAD_REQUEST} 的 API 异常
     */
    public static ApiException invalidParam(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", message);
    }

    /**
     * 创建时间窗超限异常。
     *
     * @param message 时间窗超过护栏上限的具体原因
     * @return HTTP 状态码为 {@link HttpStatus#BAD_REQUEST}、错误码 RANGE_TOO_LARGE 的 API 异常
     */
    public static ApiException rangeTooLarge(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "RANGE_TOO_LARGE", message);
    }

    /**
     * 创建未授权访问异常。
     *
     * @param message 身份认证失败或缺少认证信息的具体原因
     * @return HTTP 状态码为 {@link HttpStatus#UNAUTHORIZED} 的 API 异常
     */
    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    /**
     * 获取响应使用的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 获取业务错误码。
     *
     * @return 业务错误码
     */
    public String code() {
        return code;
    }
}