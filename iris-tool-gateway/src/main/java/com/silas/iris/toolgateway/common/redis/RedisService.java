package com.silas.iris.toolgateway.common.redis;

import cn.hutool.core.util.StrUtil;
import com.silas.iris.toolgateway.common.exception.ToolCallsExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的通用计数/限流服务。
 * <p>
 * 目前承载两条彼此独立的工具调用次数限流：模板通道每个 incident 在 {@link #ttl} 时间窗口内
 * 最多允许 {@link #TOOL_CALLS_LIMIT} 次调用；raw 逃生通道单独计数、限额更严格
 * （{@link #RAW_TOOL_CALLS_LIMIT} 次），两者使用不同的 Redis key，互不挤占对方额度。
 * 超限即视为该 incident 对应通道的调用预算耗尽。
 *
 * @author silas
 * @since 2026/8/1 20:40
 */
@Service
@Slf4j
public class RedisService {

    private final StringRedisTemplate redisTemplate;

    private static final RedisScript<Long> CONSUME_TOOL_CALLS_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/consume_tool_calls.lua"), Long.class);
    private static final String CONSUME_TOOL_CALLS_KEY = "consume_tool_calls:template:{incident_id}";
    private static final Integer TOOL_CALLS_LIMIT = 30;
    private static final String CONSUME_RAW_TOOL_CALLS_KEY = "consume_tool_calls:raw:{incident_id}";
    private static final Integer RAW_TOOL_CALLS_LIMIT = 5;
    private static final Duration ttl = Duration.ofHours(24);

    public RedisService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 消费一次指定 incident 的模板通道工具调用配额（基于 {@code consume_tool_calls.lua} 的原子 incr+expire）。
     *
     * @param incidentId 事件 ID，用于拼装 Redis key，按 incident 维度独立限流
     * @return 本次消费后剩余的可用次数
     * @throws ToolCallsExceededException 该 incident 在当前 ttl 窗口内的模板通道调用次数已耗尽
     */
    public Long consume_tool_calls(String incidentId) {
        return consume(CONSUME_TOOL_CALLS_KEY, TOOL_CALLS_LIMIT, incidentId);
    }

    /**
     * 消费一次指定 incident 的 raw 逃生通道工具调用配额。与 {@link #consume_tool_calls} 使用
     * 独立的 key 和限额，两条通道的预算互不挤占。
     *
     * @param incidentId 事件 ID，用于拼装 Redis key，按 incident 维度独立限流
     * @return 本次消费后剩余的可用次数
     * @throws ToolCallsExceededException 该 incident 在当前 ttl 窗口内的 raw 通道调用次数已耗尽
     */
    public Long consume_raw_tool_calls(String incidentId) {
        return consume(CONSUME_RAW_TOOL_CALLS_KEY, RAW_TOOL_CALLS_LIMIT, incidentId);
    }

    /**
     * 两条通道共用的原子 incr+expire 消费逻辑，仅 key 模板与限额不同。
     */
    private Long consume(String keyTemplate, int limit, String incidentId) {
        String key = StrUtil.replace(keyTemplate, "{incident_id}", incidentId);
        Long remaining = redisTemplate.execute(
                CONSUME_TOOL_CALLS_SCRIPT,
                List.of(key),
                StrUtil.toString(limit), StrUtil.toString(ttl.toSeconds()));

        if (remaining == null || remaining < 0) {
            throw new ToolCallsExceededException(incidentId);
        }

        return remaining;
    }
}
