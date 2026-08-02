package com.silas.iris.toolgateway.common.web;

import com.silas.iris.toolgateway.common.constant.HeaderConstants;
import com.silas.iris.toolgateway.common.exception.MissingRequiredHeaderException;
import com.silas.iris.toolgateway.common.exception.RawQueryRejectedException;
import com.silas.iris.toolgateway.common.exception.ToolCallsExceededException;
import com.silas.iris.toolgateway.common.exception.UnauthorizedException;
import com.silas.iris.toolgateway.common.exception.UnknownServiceException;
import com.silas.iris.toolgateway.common.exception.UnknownTemplateException;
import com.silas.iris.toolgateway.common.result.ApiEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器：把各类异常统一翻译成 {@link ApiEnvelope} 信封 + 对应 HTTP 状态码，
 * 避免每个工具 controller 各自重复一套 try/catch 映射逻辑。
 * <p>
 * 映射规则：
 * <ul>
 *   <li>参数校验失败（{@link MethodArgumentNotValidException}）→ 400，Result.fail</li>
 *   <li>未知 templateKey/service（{@link UnknownTemplateException}/{@link UnknownServiceException}）→ 400，Result.fail</li>
 *   <li>鉴权失败（{@link UnauthorizedException}）→ 401，Result.fail</li>
 *   <li>缺少必需请求头（{@link MissingRequiredHeaderException}）→ 400，Result.fail</li>
 *   <li>raw 裸查询未通过 label matcher 强校验（{@link RawQueryRejectedException}）→ 403，Result.fail</li>
 *   <li>工具调用次数超限（{@link ToolCallsExceededException}）→ 429，Result.fail</li>
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

    @ExceptionHandler({UnknownTemplateException.class, UnknownServiceException.class})
    public ResponseEntity<ApiEnvelope<?>> handleUnknownWhitelistKey(RuntimeException e) {
        // 未知 templateKey / service：白名单查表未命中，明确 400，不能落进兜底 500
        return ResponseEntity.badRequest().body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUnauthorized(UnauthorizedException e) {
        // 缺 token / token 不匹配：明确 401，不能落进兜底 500
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(MissingRequiredHeaderException.class)
    public ResponseEntity<ApiEnvelope<?>> handleMissingHeader(MissingRequiredHeaderException e) {
        // 缺 X-Incident-Id / X-Agent-Role：请求本身不合法，明确 400，不能落进兜底 500
        return ResponseEntity.badRequest().body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(RawQueryRejectedException.class)
    public ResponseEntity<ApiEnvelope<?>> handleRawQueryRejected(RawQueryRejectedException e) {
        // raw 裸查询未带 label matcher：请求本身合法，但被范围校验策略拒绝，明确 403，不能落进兜底 500
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(ToolCallsExceededException.class)
    public ResponseEntity<ApiEnvelope<?>> handleToolCallsExceeded(ToolCallsExceededException e) {
        // 当前 incident 的调用预算已耗尽，使用标准限流状态码，不能落进兜底 500
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamClientError(HttpClientErrorException e) {
        // 400/422 等：请求本身（参数或 PromQL 表达式）有问题，不是上游不可用
        log.error("Prometheus 请求返回客户端错误，status: {}", e.getStatusCode(), e);
        return ResponseEntity.status(e.getStatusCode()).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(HttpServerErrorException.ServiceUnavailable.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamUnavailable(
            HttpServerErrorException.ServiceUnavailable e, HttpServletRequest request) {
        // 503：查询超时或被中止，属于上游不可用，走降级而非真错误，HTTP 状态码仍为 200
        log.error("Prometheus 服务不可用，status: {}", e.getStatusCode(), e);
        String reason = "prometheus query timeout or aborted: " + e.getMessage();
        markAuditDegraded(request, reason);
        return ResponseEntity.ok(ApiEnvelope.degraded(null, reason, null));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamUnreachable(
            ResourceAccessException e, HttpServletRequest request) {
        // 连接失败/读超时，连 HTTP 响应都没拿到，同样属于上游不可用
        log.error("Prometheus 连接或读取失败", e);
        String reason = "prometheus unreachable: " + e.getMessage();
        markAuditDegraded(request, reason);
        return ResponseEntity.ok(ApiEnvelope.degraded(null, reason, null));
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ApiEnvelope<?>> handleUpstreamServerError(HttpServerErrorException e) {
        // 503 以外的上游 5xx，不属于已知的降级场景，按真错误处理
        log.error("Prometheus 请求返回服务端错误，status: {}", e.getStatusCode(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiEnvelope.fail(e.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiEnvelope<?>> handleUnknown(Exception e) {
        // 未识别的异常视为网关自身问题（代码 bug/解析失败等），不能伪装成降级
        log.error("unhandled exception in tool gateway", e);
        return ResponseEntity.internalServerError().body(ApiEnvelope.fail(e.getMessage(), null));
    }

    private void markAuditDegraded(HttpServletRequest request, String reason) {
        request.setAttribute(HeaderConstants.AUDIT_DEGRADED_ATTR, true);
        request.setAttribute(HeaderConstants.AUDIT_RESPONSE_SUMMARY_ATTR,
                Map.of("ok", true, "degraded", true, "reason", reason));
    }
}
