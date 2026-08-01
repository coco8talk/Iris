package com.silas.iris.toolgateway.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一返回结果对象（ApiEnvelope）。
 * <p>
 * 三种状态互斥，构造时只应使用下面对应的静态工厂方法之一：
 * <ul>
 *   <li>{@link #ok}：正常返回，ok=true，degraded=false</li>
 *   <li>{@link #degraded}：上游超时/不可用导致数据不完整，但请求本身处理成功，ok=true，degraded=true，HTTP 状态码仍为 200</li>
 *   <li>{@link #fail}：参数校验失败或内部异常等真错误，ok=false，degraded=false，由调用方（GlobalExceptionHandler）决定对应的 4xx/5xx 状态码</li>
 * </ul>
 *
 * @author silas
 * @since 2026/7/31 17:25
 */

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiEnvelope<T> {
    /**
     * 本次调用是否成功执行。
     * 只要流程正常走完，给出了结果，就是 true；
     * 只有真出错，例如：参数校验失败、内部异常，才是 false
     */
    private boolean ok;

    /**
     * 本次调用是否被降级处理。
     */
    private boolean degraded;

    /**
     * 降级原因，仅在 degraded=true 时有值
     */
    private String degradedReason;

    /**
     * 错误信息，仅在 ok=false 时有值
     */
    private String message;

    /**
     * 返回的业务数据，如果 degraded=true，则可能返回空数据
     */
    private T data;

    /**
     * 元信息对象，耗时/截断/预算等附加信息，不属于业务数据本身
     */
    private Meta meta;

    /**
     * 响应元信息：耗时、是否截断、剩余调用预算
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Meta {
        /**
         * 本次调用总耗时，单位毫秒
         */
        private Long elapsedMs;

        /**
         * 数据是否被截断：即数据成功拿到，但原始数据量超过展示上限，被主动裁剪后返回，用于告知 agent 当前不是全量数据
         */
        private boolean truncated;

        /**
         * 本次 incident 在当前通道（模板/raw）下的剩余调用次数，用尽后网关返回 429
         */
        private int budgetRemaining;
    }


    /**
     * 正常返回：上游调用成功，数据完整（或按预期截断）。
     */
    public static <T> ApiEnvelope<T> ok(T data, Meta meta) {
        return ApiEnvelope.<T>builder()
                .ok(true)
                .degraded(false)
                .data(data)
                .meta(meta)
                .build();
    }

    /**
     * 降级返回：上游超时/不可用导致数据不完整或为空，但网关自身处理成功，HTTP 状态码仍应为 200。
     */
    public static <T> ApiEnvelope<T> degraded(T data, String reason, Meta meta) {
        return ApiEnvelope.<T>builder()
                .ok(true)
                .degraded(true)
                .degradedReason(reason)
                .data(data)
                .meta(meta)
                .build();
    }

    /**
     * 失败返回：参数校验失败或网关内部异常等真错误，HTTP 状态码应由调用方映射为对应的 4xx/5xx。
     * meta 可为空（例如校验失败发生在还没算出耗时/预算之前）。
     */
    public static <T> ApiEnvelope<T> fail(String reason, Meta meta) {
        return ApiEnvelope.<T>builder()
                .ok(false)
                .degraded(false)
                .message(reason)
                .meta(meta)
                .build();
    }

}


























