package com.silas.iris.toolgateway.common.exception;

/**
 * 调用方传入的 templateKey 不在 {@link com.silas.iris.toolgateway.metrics.utils.MetricsTemplateRegistry} 白名单内。
 * 必须 400，不能把未知 key 当 PromQL 尝试执行。
 *
 * @author silas
 * @since 2026/8/1
 */
public class UnknownTemplateException extends RuntimeException {

    public UnknownTemplateException(String templateKey) {
        super("未知的 templateKey: " + templateKey);
    }
}
