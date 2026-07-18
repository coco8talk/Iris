package com.iris.gateway.common.config;

import com.iris.gateway.common.interceptor.AuthInterceptor;
import com.iris.gateway.common.interceptor.InternalAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册工具通道与内部通道鉴权拦截器
 *
 * @author silas
 * @since 2026/7/14
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, InternalAuthInterceptor internalAuthInterceptor) {
        this.authInterceptor = authInterceptor;
        this.internalAuthInterceptor = internalAuthInterceptor;
    }

    /**
     * 工具接口 /api/v1/** 走完整鉴权（Bearer + incident + role）；
     * 内部接口 /internal/** 仅校验 Bearer（不要求 incident/role 头）；
     * knife4j 文档页（/doc.html、/v3/api-docs）不在拦截范围内。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(internalAuthInterceptor).addPathPatterns("/internal/**");
    }
}