package com.iris.gateway.common.result;

/**
 * API 统一响应封装。
 *
 * <p>用于描述接口调用是否成功、是否发生降级、响应数据以及调用过程中的元数据信息。</p>
 *
 * @param ok             接口调用是否成功
 * @param degraded       接口是否返回降级结果
 * @param degradedReason 降级原因；未发生降级时为 {@code null}
 * @param data           接口响应数据；降级且无可用数据时为 {@code null}
 * @param meta           接口调用元数据信息
 * @param <T>            响应数据类型
 */
public record ApiEnvelope<T>(
        boolean ok,
        boolean degraded,
        String degradedReason,
        T data,
        Meta meta
) {

    /**
     * API 调用元数据信息。
     *
     * @param elapsedMs       接口调用耗时，单位：毫秒
     * @param truncated       响应数据是否因长度或数量限制被截断
     * @param budgetRemaining 剩余预算；当前固定为 {@code -1}，
     *                        待 T18 预算限流能力接管后返回真实值
     */
    public record Meta(
            long elapsedMs,
            boolean truncated,
            int budgetRemaining
    ) {
    }

    /**
     * 创建正常成功响应。
     *
     * <p>当前剩余预算固定为 {@code -1}，待 T18 预算限流能力接管后改为真实值。</p>
     *
     * @param data      响应数据
     * @param elapsedMs 接口调用耗时，单位：毫秒
     * @param <T>       响应数据类型
     * @return 正常成功响应
     */
    public static <T> ApiEnvelope<T> ok(T data, long elapsedMs) {
        return new ApiEnvelope<>(
                true,
                false,
                null,
                data,
                new Meta(elapsedMs, false, -1)
        );
    }

    /**
     * 创建降级成功响应。
     *
     * <p>降级响应仍表示请求已被正常处理，因此 {@code ok} 为 {@code true}；
     * 由于当前未返回可用业务数据，{@code data} 为 {@code null}。</p>
     *
     * <p>当前剩余预算固定为 {@code -1}，待 T18 预算限流能力接管后改为真实值。</p>
     *
     * @param reason    降级原因
     * @param elapsedMs 接口调用耗时，单位：毫秒
     * @param <T>       响应数据类型
     * @return 降级成功响应
     */
    public static <T> ApiEnvelope<T> degraded(String reason, long elapsedMs) {
        return new ApiEnvelope<>(
                true,
                true,
                reason,
                null,
                new Meta(elapsedMs, false, -1)
        );
    }
}