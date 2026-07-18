package com.iris.gateway.common.exception;

/**
 * 上游不可用异常：已识别、可安全降级的上游（Prometheus/Loki/Zipkin）超时或连接失败(H3)。
 *
 * <p>由各上游 client 抛出，Controller 捕获后返回 200 + degraded:true，
 * 而非 5xx；网关自身编程/存储/契约错误不得使用本异常伪装成降级。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message) {
        super(message);
    }

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
