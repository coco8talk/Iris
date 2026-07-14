package com.iris.gateway.common.enums;

import lombok.Getter;

/**
 * 工具模板枚举
 *
 * @author silas
 * @since 2026/7/14 03:39
 */
@Getter
public enum TemplateEnum {

    GET_TOPOLOGY("get_topology", "查询服务拓扑"),
    GET_SERVICE_DETAIL("get_service_detail", "查询服务详情");

    /** 模板标识，即请求体 template 字段的合法取值 */
    private final String template;

    /** 模板用途说明 */
    private final String description;

    TemplateEnum(String template, String description) {
        this.template = template;
        this.description = description;
    }

    /**
     * 按模板标识查找枚举。
     *
     * @param template 模板标识
     * @return 匹配的枚举；未匹配时返回 null
     */
    public static TemplateEnum of(String template) {
        for (TemplateEnum e : values()) {
            if (e.template.equals(template)) {
                return e;
            }
        }
        return null;
    }
}
