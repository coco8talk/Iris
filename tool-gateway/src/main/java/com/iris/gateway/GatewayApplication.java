package com.iris.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Iris Gateway 服务启动类。
 *
 * <p>负责启动 Spring Boot 应用，并触发组件扫描、自动配置及配置类加载。</p>
 *
 * @author Iris
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * 应用程序入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) throws IOException {
        // sqlite-jdbc 不会创建缺失的父目录,先建好数据源相对路径 data/ 所在目录(已存在时无副作用)
        Files.createDirectories(Path.of("data"));
        SpringApplication.run(GatewayApplication.class, args);
    }
}
