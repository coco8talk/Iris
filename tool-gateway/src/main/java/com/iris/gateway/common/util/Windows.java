package com.iris.gateway.common.util;

import com.iris.gateway.common.exception.ApiException;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间窗解析工具（§0.3 通用契约）。
 *
 * <p>模板通道时间窗枚举固定为 {@code 15m|30m|1h|6h}。本类是 T18 统一
 * {@code WindowParser} 落地前的最小实现，供 changes/metrics/logs/trace 共用，
 * 避免四处重复解析逻辑。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
public final class Windows {

    /** 合法时间窗枚举 → 时长 */
    private static final Map<String, Duration> ENUM = Map.of(
            "15m", Duration.ofMinutes(15),
            "30m", Duration.ofMinutes(30),
            "1h", Duration.ofHours(1),
            "6h", Duration.ofHours(6));

    /** 通用时长格式：正整数 + 单位 s/m/h（用于带上限校验的解析） */
    private static final Pattern DURATION = Pattern.compile("^(\\d+)([smh])$");

    /** 模板通道时间窗上限：6 小时 */
    private static final Duration CEILING = Duration.ofHours(6);

    private Windows() {
    }

    /**
     * 严格枚举校验：window 必须是 {@code 15m|30m|1h|6h} 之一。
     *
     * @param window 请求时间窗
     * @return 对应时长
     * @throws ApiException 非枚举值时抛 400 INVALID_PARAM
     */
    public static Duration required(String window) {
        Duration d = window == null ? null : ENUM.get(window);
        if (d == null) {
            throw ApiException.invalidParam("window must be one of 15m|30m|1h|6h");
        }
        return d;
    }

    /**
     * 带 6h 上限的解析：接受一般时长格式，超过 6h 抛 RANGE_TOO_LARGE。
     *
     * <p>与 {@link #required} 的区别：此方法先接受形如 {@code 12h} 的合法格式，
     * 再判定其超限，从而返回 400 {@code RANGE_TOO_LARGE} 而非 {@code INVALID_PARAM}
     * （metrics 契约要求，T14）。</p>
     *
     * @param window 请求时间窗
     * @return 对应时长
     * @throws ApiException 格式非法抛 400 INVALID_PARAM；超过 6h 抛 400 RANGE_TOO_LARGE
     */
    public static Duration ceiling6h(String window) {
        if (window == null) {
            throw ApiException.invalidParam("window is required");
        }
        Matcher m = DURATION.matcher(window);
        if (!m.matches()) {
            throw ApiException.invalidParam("window format must be <int>[s|m|h], e.g. 30m");
        }
        long value = Long.parseLong(m.group(1));
        Duration d = switch (m.group(2)) {
            case "s" -> Duration.ofSeconds(value);
            case "m" -> Duration.ofMinutes(value);
            default -> Duration.ofHours(value);
        };
        if (d.compareTo(CEILING) > 0) {
            throw ApiException.rangeTooLarge("window must be <= 6h");
        }
        return d;
    }
}
