package com.silas.iris.toolgateway.common.web;

import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常统一翻译成 {@link ApiEnvelope} 信封 + 对应 HTTP 状态码，
 * 避免每个工具 controller 各自重复一套 try/catch 映射逻辑。
 * <p>
 * 映射规则：
 * <ul>
 *   <li>参数校验失败（{@link MethodArgumentNotValidException}）→ 400，Result.fail</li>
 *   <li>上游返回 4xx（{@link HttpClientErrorException}，如 400/422）→ 透传原状态码，Result.fail</li>
 *   <li>上游返回 503（查询超时/中止，{@link HttpServerErrorException.ServiceUnavailable}）→ 200，Result.degraded</li>
 *   <li>上游其余 5xx（{@link HttpServerErrorException}）→ 502，Result.fail</li>
 *   <li>网络层连接失败/超时，未拿到 HTTP 响应（{@link ResourceAccessException}）→ 200，Result.degraded</li>
 *   <li>其余未识别异常 → 500，Result.fail</li>
 * </ul>
 *
 * @author silas
 * @since 2026/7/31 17:25
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiEnvelope<?>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiEnvelope.fail(message, null));
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamClientError(HttpClientErrorException e) {
        // 400/422 等：请求本身（参数或 PromQL 表达式）有问题，不是上游不可用
        return ResponseEntity.status(e.getStatusCode()).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(HttpServerErrorException.ServiceUnavailable.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamUnavailable(HttpServerErrorException.ServiceUnavailable e) {
        // 503：查询超时或被中止，属于上游不可用，走降级而非真错误，HTTP 状态码仍为 200
        return ResponseEntity.ok(ApiEnvelope.degraded(null, "prometheus query timeout or aborted: " + e.getMessage(), null));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamUnreachable(ResourceAccessException e) {
        // 连接失败/读超时，连 HTTP 响应都没拿到，同样属于上游不可用
        return ResponseEntity.ok(ApiEnvelope.degraded(null, "prometheus unreachable: " + e.getMessage(), null));
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamServerError(HttpServerErrorException e) {
        // 503 以外的上游 5xx，不属于已知的降级场景，按真错误处理
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<?>> handleUnknown(Exception e) {
        // 未识别的异常视为网关自身问题（代码 bug/解析失败等），不能伪装成降级
        log.error("unhandled exception in tool gateway", e);
        return ResponseEntity.internalServerError().body(ApiEnvelope.fail(e.getMessage(), null));
    }
}
