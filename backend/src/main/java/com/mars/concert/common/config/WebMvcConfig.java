package com.mars.concert.common.config;

import com.mars.concert.common.web.CurrentUserArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 实现 WebMvcConfigurer 来定制 SpringMVC 行为。
 * 当前只配 CORS；登录功能在这里注册 @CurrentUser 参数解析器。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** @CurrentUser 参数解析器（把当前登录用户注入 Controller 参数） */
    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    /**
     * 跨域配置。
     * 开发时前端可能用 5500(VSCode Live Server) 或直接打开 file:// 页面，
     * 浏览器同源策略会拦截不同源的 AJAX，需要后端明确允许。
     * 生产环境前后端同源(都走 Nginx/8080)后，可把 allowedOriginPatterns 收窄为正式域名。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")                 // 只对接口放行跨域
                .allowedOriginPatterns("*")            // 允许任意来源（开发期）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")                   // 允许 Authorization 等自定义头
                .allowCredentials(true)                // 允许带 cookie
                .maxAge(3600);                         // 预检请求缓存1小时
    }
}
