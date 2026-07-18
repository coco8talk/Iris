package com.iris.gateway.changes.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.iris.gateway.changes.model.entity.ChangeEvent;
import com.iris.gateway.changes.vo.ChangeVO;

import java.time.Duration;
import java.util.List;

/**
 * 变更事件读写：工具通道查询、内部写入、冷启动背景种子(T17)。
 *
 * @author silas
 * @since 2026/7/18
 */
public interface ChangeEventService extends IService<ChangeEvent> {

    /**
     * 按时间窗（可选服务名过滤）查询变更事件，按时间倒序。
     *
     * @param window  时间窗，查询 [now-window, now] 内的变更
     * @param service 服务名过滤；为空则不过滤
     * @return 变更视图列表
     */
    List<ChangeVO> query(Duration window, String service);

    /**
     * 写入 6 条近 6 小时内分散的正常背景变更（防"有变更=必是根因"捷径，§4.6②）。
     *
     * <p>调用方需保证表为空；仅由 ChangeSeeder 冷启动及测试重置调用。</p>
     */
    void seedBackground();
}
