package com.iris.gateway.changes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.iris.gateway.changes.mapper.ChangeEventMapper;
import com.iris.gateway.changes.model.entity.ChangeEvent;
import com.iris.gateway.changes.service.ChangeEventService;
import com.iris.gateway.changes.vo.ChangeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 变更事件读写实现，基于 MyBatis-Plus 通用 Service。
 *
 * @author silas
 * @since 2026/7/18
 */
@Slf4j
@Service
public class ChangeEventServiceImpl extends ServiceImpl<ChangeEventMapper, ChangeEvent> implements ChangeEventService {

    @Override
    public List<ChangeVO> query(Duration window, String service) {
        // ts 为同一 ISO 8601 UTC 格式，字典序即时间序，可直接用于范围比较
        String cutoff = Instant.now().minus(window).toString();
        QueryWrapper<ChangeEvent> wrapper = new QueryWrapper<ChangeEvent>()
                .ge("ts", cutoff)
                .orderByDesc("ts");
        if (service != null && !service.isBlank()) {
            wrapper.eq("service", service);
        }
        return list(wrapper).stream()
                .map(e -> new ChangeVO(
                        e.getChangeId(), e.getTs(), e.getType(),
                        e.getService(), e.getSummary(), e.getOperator()))
                .toList();
    }

    @Override
    @Transactional
    public void seedBackground() {
        Instant now = Instant.now();
        // 6 条近 6 小时内分散的正常背景变更：2 发版 / 2 配置调优 / 2 例行重启，
        // order-service 恰 2 条。分散于 6h 窗口内以便 window<=6h 契约下可见。
        List<ChangeEvent> seeds = List.of(
                seed("chg-seed-1", now, 25, "deploy", "order-service",
                        "order-service v1.8.3 灰度发版完成", "wang.lei"),
                seed("chg-seed-2", now, 95, "config_change", "order-service",
                        "order-service HikariCP 连接池上限 20→30", "li.na"),
                seed("chg-seed-3", now, 150, "deploy", "gateway",
                        "gateway v2.1.0 常规发版", "zhang.wei"),
                seed("chg-seed-4", now, 215, "restart", "inventory-service",
                        "inventory-service 例行滚动重启", "chen.yu"),
                seed("chg-seed-5", now, 290, "config_change", "inventory-service",
                        "inventory-service 缓存 TTL 300s→600s 调优", "liu.fang"),
                seed("chg-seed-6", now, 350, "restart", "payment-service",
                        "payment-service 节点例行重启", "zhao.min"));
        saveBatch(seeds);
        log.info("change_event seeded: {} background changes", seeds.size());
    }

    private ChangeEvent seed(String id, Instant now, long minutesAgo,
                             String type, String service, String summary, String operator) {
        ChangeEvent e = new ChangeEvent();
        e.setChangeId(id);
        e.setTs(now.minus(Duration.ofMinutes(minutesAgo)).toString());
        e.setType(type);
        e.setService(service);
        e.setSummary(summary);
        e.setOperator(operator);
        return e;
    }
}
