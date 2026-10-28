package com.mars.concert.common.config;

import com.mars.concert.community.ws.DanmakuHandler;
import com.mars.concert.community.ws.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 第6卷：WebSocket 配置（原生 Handler，不用 STOMP，便于理解握手与会话生命周期）。
 * 端点：/ws/danmaku/{eventId}，token 走 query（浏览器 WS 无法自定义头）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final DanmakuHandler danmakuHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(danmakuHandler, "/ws/danmaku/{eventId}")
                .addInterceptors(jwtHandshakeInterceptor)
                // 开发期允许任意来源；生产应收窄为正式域名
                .setAllowedOriginPatterns("*");
    }
}
