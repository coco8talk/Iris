package com.iris.gateway.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 网关全局异常处理器。
 *
 * <p>统一捕获控制器处理过程中抛出的异常，并将其转换为标准 API 错误响应，
 * 避免向调用方暴露内部异常堆栈或实现细节。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 日志记录器。
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * API 错误响应体。
     *
     * @param ok        请求是否成功；错误响应固定为 {@code false}
     * @param code      业务错误码
     * @param message   面向调用方的错误描述
     * @param retryable 当前错误是否支持调用方重试
     */
    public record ErrorBody(
            boolean ok,
            String code,
            String message,
            boolean retryable
    ) {
    }

    /**
     * 处理可预期的 API 业务异常。
     *
     * <p>响应状态码和业务错误码由 {@link ApiException} 提供。
     * 当前此类异常均视为不可重试。</p>
     *
     * @param e API 业务异常
     * @return 标准 API 错误响应
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException e) {
        log.error("api business error, code={}, message={}", e.code(), e.getMessage(), e);
        return ResponseEntity.status(e.status())
                .body(new ErrorBody(false, e.code(), e.getMessage(), false));
    }

    /**
     * 处理请求参数校验失败的异常。
     *
     * <p>由 {@code @Valid} 参数上的 Bean Validation 注解校验失败触发，
     * 返回首个校验失败字段及原因。</p>
     *
     * @param e 参数校验异常
     * @return HTTP 400 参数错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("invalid request parameter");
        log.error("request parameter validation failed: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody(false, "INVALID_PARAM", message, false));
    }

    /**
     * 处理请求体无法解析的异常。
     *
     * <p>常见原因包括请求体不是合法 JSON、字段类型不匹配或 JSON 结构不完整。
     * 为避免泄露底层反序列化细节，统一返回固定错误描述。</p>
     *
     * @param e 请求体解析异常
     * @return HTTP 400 参数错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException e) {
        log.error("request body is not readable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody(
                        false,
                        "INVALID_PARAM",
                        "request body is not valid JSON",
                        false
                ));
    }

    /**
     * 处理未被其他异常处理器匹配的系统异常。
     *
     * <p>网关自身发生的未知异常必须返回 HTTP 500，不得伪装为降级成功响应，
     * 以避免调用方将系统故障误判为正常业务结果，参见规范 §0.3。</p>
     *
     * <p>完整异常堆栈仅记录在服务端日志中，对外返回固定错误信息，
     * 防止内部实现细节泄露。</p>
     *
     * @param e 未知系统异常
     * @return HTTP 500 网关内部错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleUnknown(Exception e) {
        log.error("gateway internal error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody(
                        false,
                        "GATEWAY_ERROR",
                        "unexpected gateway error",
                        false
                ));
    }
}
