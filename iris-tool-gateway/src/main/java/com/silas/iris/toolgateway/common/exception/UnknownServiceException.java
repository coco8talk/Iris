package com.silas.iris.toolgateway.common.exception;

/**
 * 调用方传入的 service 不在 {@link com.silas.iris.toolgateway.metrics.utils.MetricsTemplateUtil} 白名单内。
 * 必须 400，避免网关对未登记的 service 发起任意 PromQL 查询。
 *
 * @author silas
 * @since 2026/8/1
 */
public class UnknownServiceException extends RuntimeException {

    public UnknownServiceException(String service) {
        super("未知的 service: " + service);
    }
}
