package com.iris.gateway.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * knife4j / OpenAPI 接口文档配置，文档访问地址：/doc.html
 *
 * @author silas
 * @since 2026/7/14
 */
@Configuration
public class Knife4jConfig {

    /**
     * 接口文档基础信息。
     *
     * @return OpenAPI 文档描述对象
     */
    @Bean
    public OpenAPI toolGatewayOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Iris Tool Gateway API")
                        .description("面向 Agent 的运维工具网关：提供 CMDB 拓扑与服务详情等查询能力，"
                                + "所有 /api/v1/** 接口需携带 Bearer Token、X-Incident-Id、X-Agent-Role 请求头")
                        .version("0.1.0"));
    }
}
