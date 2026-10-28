package com.mars.concert.community.ws;

import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket 握手拦截器。
 * 浏览器原生 WebSocket 不能自定义 Authorization 头，所以 token 放 query（?token=xxx），
 * 握手时解析并做与 HTTP 过滤器同款的「JWT 验签 + Redis 登录态 jti 比对」，
 * 通过则把 userId 放进 attributes 供 Handler 使用；eventId 从路径段提取。
 * 未带 token/校验失败也放行（游客可看弹幕），发送时由 Handler 要求登录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   org.springframework.web.socket.WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        Map<String, String> params = parseQuery(query);

        // eventId 取路径最后一段：/ws/danmaku/{eventId}
        String path = request.getURI().getPath();
        String eventId = path.substring(path.lastIndexOf('/') + 1);
        attributes.put("eventId", eventId);

        String token = params.get("token");
        if (token != null && !token.isBlank()) {
            try {
                Claims c = jwtUtil.parse(token);
                Long uid = Long.valueOf(c.getSubject());
                String device = c.get("device", String.class);
                // 与 JwtAuthenticationFilter 同款：Redis 登录态存在且 jti 匹配才算有效登录
                Object cached = redisTemplate.opsForValue()
                        .get(RedisKeys.TOKEN_ACCESS + uid + ":" + device);
                if (cached != null && c.getId().equals(cached.toString())) {
                    attributes.put("userId", uid);
                }
            } catch (Exception e) {
                log.debug("WebSocket token 校验失败，按游客处理: {}", e.getMessage());
            }
        }
        return true;   // 游客也放行：可以看弹幕，发弹幕时 Handler 再要求登录
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需后置处理
    }

    /** 解析 a=1&b=2 形式的 query 串（值不含 & = 的学习期简化实现） */
    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) map.put(pair.substring(0, idx), pair.substring(idx + 1));
        }
        return map;
    }
}
