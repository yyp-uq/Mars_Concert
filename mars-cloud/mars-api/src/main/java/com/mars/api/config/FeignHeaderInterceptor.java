package com.mars.api.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 请求头透传（第8卷 §10）：Feign 调用默认不带原请求头，
 * 这里把网关透传的 X-Trace-Id / X-User-Id 继续传给下游服务，
 * 跨服务日志一条 traceId 串到底，下游也能拿到用户身份。
 */
@Component
public class FeignHeaderInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) return;   // 脱离请求线程（异步/定时任务）时为空
        HttpServletRequest req = attrs.getRequest();
        String traceId = req.getHeader("X-Trace-Id");
        String userId = req.getHeader("X-User-Id");
        if (traceId != null) template.header("X-Trace-Id", traceId);
        if (userId != null) template.header("X-User-Id", userId);
    }
}
