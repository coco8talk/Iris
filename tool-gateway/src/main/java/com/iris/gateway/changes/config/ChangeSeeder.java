package com.iris.gateway.changes.config;

import com.iris.gateway.changes.service.ChangeEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时若 change_event 表为空，写入 6 条背景变更种子(T17)。
 *
 * <p>非空则跳过，保证重启幂等、不覆盖已写入的真实/伪装变更。</p>
 *
 * @author silas
 * @since 2026/7/18
 */
@Slf4j
@Component
public class ChangeSeeder implements ApplicationRunner {

    private final ChangeEventService changeEventService;

    public ChangeSeeder(ChangeEventService changeEventService) {
        this.changeEventService = changeEventService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long count = changeEventService.count();
        if (count == 0) {
            changeEventService.seedBackground();
        } else {
            log.info("change_event already has {} rows, skip seeding", count);
        }
    }
}
