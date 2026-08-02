package com.silas.iris.toolgateway.metrics.utils;

import com.silas.iris.toolgateway.metrics.model.enums.MetricTemplate;
import lombok.extern.slf4j.Slf4j;

/**
 * PromQL 模板渲染工具：按 {@code templateKey} 查找 {@link MetricTemplate} 白名单模板，
 * 替换 {@code {service}}/{@code {window}} 占位符，得到可执行的 PromQL 表达式。
 *
 * @author silas
 * @since 2026/8/1 17:54
 */
@Slf4j
public class MetricsTemplateUtil {

    /**
     * 渲染指定模板的 PromQL 表达式。
     *
     * @param templateKey 模板 key，须命中 {@link MetricTemplate} 白名单
     * @param service     目标服务名，用于替换 {@code {service}} 占位符
     * @param window      查询时间窗口（秒），用于替换 {@code {window}} 占位符
     * @return 替换完占位符、可直接执行的 PromQL 表达式
     * @throws com.silas.iris.toolgateway.common.exception.UnknownTemplateException templateKey 不在白名单内
     */
    public static String render(String templateKey, String service, String window) {

        // todo cmdb校验
        log.info("开始填充 PromQL：render templateKey: {}, service: {}", templateKey, service);
        MetricTemplate metricTemplate = MetricTemplate.fromKey(templateKey);
        return metricTemplate.getPromqlTemplate()
                .replace("{service}", service)
                .replace("{window}", window);
    }
}
