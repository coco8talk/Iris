package com.silas.iris.toolgateway.cmdb.constant;

/**
 * Nacos 3.x 服务目录查询相关的固定端点与分页边界。
 *
 * @author silas
 * @since 2026/8/2
 */
public final class NacosConstants {

    private NacosConstants() {
    }

    /**
     * 查询服务列表的 Console API 路径。
     */
    public static final String SERVICE_LIST_PATH = "/v3/console/ns/service/list";

    /**
     * 查询指定服务实例列表的 Client API 路径。
     */
    public static final String INSTANCE_LIST_PATH = "/nacos/v3/client/ns/instance/list";

    /**
     * Nacos 服务列表接口允许的最大单页条目数。
     */
    public static final int SERVICE_PAGE_SIZE = 500;

    /**
     * Nacos 3.x Open API 成功响应码。
     */
    public static final int SUCCESS_CODE = 0;
}
