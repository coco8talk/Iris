package com.silas.iris.toolgateway.common.exception;

/**
 * raw 逃生通道的裸查询未通过 label matcher 强校验时抛出。
 * 必须 403，不能落进兜底 500，也不能跟 400（参数格式错误）混淆——
 * 这是"请求本身合法，但内容被范围校验策略拒绝"。
 *
 * @author silas
 * @since 2026/8/2
 */
public class RawQueryRejectedException extends RuntimeException {

    public RawQueryRejectedException(String message) {
        super(message);
    }
}
