package com.mars.gateway.filter;

import com.mars.gateway.security.JwtLite;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 鉴权下推（第8卷 §7）：token 校验收口到网关，
 * 校验通过把 X-User-Id / X-Trace-Id 透传给下游，内部服务默认信任。
 */
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtLite jwtLite;
    private final ReactiveStringRedisTemplate redis;

    /** 白名单：登录/注册/公开查询/接口文档 */
    private static final List<String> WHITE = List.of(
            "/api/v1/auth/", "/api/v1/faqs", "/doc.html", "/v3/api-docs",
            "/api/v1/events/home"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getURI().getPath();

        // 白名单 + 场次相关 GET 公开查询
        if (WHITE.stream().anyMatch(path::startsWith)
                || (req.getMethod() == HttpMethod.GET && path.startsWith("/api/v1/events"))) {
            return chain.filter(exchange);
        }
        if (!path.startsWith("/api/")) return chain.filter(exchange);   // 静态资源放行

        String token = req.getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            return unauthorized(exchange, "未登录");
        }
        try {
            Claims c = jwtLite.parse(token.substring(7));
            Long uid = Long.valueOf(c.getSubject());
            String device = c.get("device", String.class);
            String key = "mars:token:access:" + uid + ":" + device;
            return redis.opsForValue().get(key).defaultIfEmpty("").flatMap(jti -> {
                // mars-common 的 RedisTemplate 用 Jackson 值序列化，字符串会带双引号（"uuid"）；
                // 网关是 ReactiveStringRedisTemplate 读原始值，这里去掉引号再做 jti 比对
                String cached = jti;
                if (cached.length() >= 2 && cached.startsWith("\"") && cached.endsWith("\"")) {
                    cached = cached.substring(1, cached.length() - 1);
                }
                if (!c.getId().equals(cached)) return unauthorized(exchange, "登录已失效");
                // 透传用户身份与链路ID给下游
                ServerHttpRequest mutated = req.mutate()
                        .header("X-User-Id", String.valueOf(uid))
                        .header("X-Username", c.getSubject())
                        .header("X-Trace-Id", traceId())
                        .build();
                return chain.filter(exchange.mutate().request(mutated).build());
            });
        } catch (Exception e) {
            return unauthorized(exchange, "token无效或已过期");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":1401,\"message\":\"" + msg + "\",\"data\":null}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = resp.bufferFactory().wrap(body);
        return resp.writeWith(Mono.just(buffer));
    }

    private String traceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
