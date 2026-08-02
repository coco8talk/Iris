package com.silas.iris.toolgateway.common.redis;

import cn.hutool.core.util.StrUtil;
import com.silas.iris.toolgateway.common.exception.ToolCallsExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的通用计数/限流服务。
 * <p>
 * 目前只承载工具调用次数限流：每个 incident 在 {@link #ttl} 时间窗口内最多允许
 * {@link #TOOL_CALLS_LIMIT} 次工具调用，超限即视为该 incident 的调用预算耗尽。
 *
 * @author silas
 * @since 2026/8/1 20:40
 */
@Service
@Slf4j
public class RedisService {

    private final RedisTemplate<Object, Object> redisTemplate;

    private static final RedisScript<Long> CONSUME_TOOL_CALLS_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/consume_tool_calls.lua"), Long.class);
    private static final String CONSUME_TOOL_CALLS_KEY = "consume_tool_calls:{incident_id}";
    private static final Integer TOOL_CALLS_LIMIT = 30;
    private static final Duration ttl = Duration.ofHours(24);

    public RedisService(RedisTemplate<Object, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 消费一次指定 incident 的工具调用配额（基于 {@code consume_tool_calls.lua} 的原子 incr+expire）。
     *
     * @param incidentId 事件 ID，用于拼装 Redis key，按 incident 维度独立限流
     * @return 本次消费后剩余的可用次数
     * @throws ToolCallsExceededException 该 incident 在当前 ttl 窗口内的调用次数已耗尽
     */
    public Long consume_tool_calls(String incidentId) {
        String key = StrUtil.replace(CONSUME_TOOL_CALLS_KEY, "{incident_id}", incidentId);
        Long remaining = redisTemplate.execute(
                CONSUME_TOOL_CALLS_SCRIPT,
                List.of(key),
                TOOL_CALLS_LIMIT, ttl.toSeconds());

        if(remaining == null || remaining < 0){
            throw new ToolCallsExceededException(incidentId);
        }

        return remaining;
    }
}
