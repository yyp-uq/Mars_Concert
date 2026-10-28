package com.mars.concert.community.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.utils.RedisService;
import com.mars.concert.community.filter.SensitiveWordFilter;
import com.mars.concert.community.model.dto.DanmakuDTO;
import com.mars.concert.community.model.message.DanmakuMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 弹幕 Handler：连接管理 + 发送限流 + 敏感词过滤 + 广播。
 *
 * 连接管理：每个场次一组会话（单体内存 ROOMS）。
 * 广播：发送方先 publish 到 Redis 频道，由各实例订阅后 localBroadcast 推本实例会话（集群场景）；
 * 单实例下这条链路同样成立（发出去绕一圈回来），逻辑统一不用区分部署形态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DanmakuHandler extends TextWebSocketHandler {

    /** 每个场次(eventId)一组会话 */
    private static final Map<Long, Set<WebSocketSession>> ROOMS = new ConcurrentHashMap<>();

    private final SensitiveWordFilter sensitiveWordFilter;
    private final RedisService redis;
    private final DanmakuRedisPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long eventId = eventId(session);
        if (eventId == null) { closeQuietly(session); return; }
        ROOMS.computeIfAbsent(eventId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("弹幕连接建立 eventId={} userId={} 在线={}", eventId,
                session.getAttributes().get("userId"), ROOMS.get(eventId).size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // ① 登录校验：游客可连可看，发送必须登录
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) { send(session, error("请先登录")); return; }
        Long eventId = eventId(session);
        if (eventId == null) { send(session, error("场次非法")); return; }

        DanmakuDTO dto;
        try {
            dto = objectMapper.readValue(message.getPayload(), DanmakuDTO.class);
        } catch (Exception e) {
            send(session, error("消息格式错误"));
            return;
        }

        // ② 限流：每人每秒 1 条（INCR + 首次置 1s 过期，>1 即超速）
        String rk = RedisKeys.DANMAKU_RATE + userId;
        Long n = redis.incr(rk);
        if (n != null && n == 1L) redis.expire(rk, 1);
        if (n != null && n > 1L) { send(session, error("发言太快了")); return; }

        // ③ 敏感词过滤：命中片段被替换为 *；过滤后为空说明整条都是违规词，直接拒绝
        String clean = sensitiveWordFilter.filter(dto.getContent());
        if (clean.isBlank()) { send(session, error("内容含违规词")); return; }

        // ④ 广播：发到 Redis 频道，由订阅者统一推各实例
        publisher.publish(eventId, new DanmakuMessage(userId, dto.getNickname(), clean, dto.getColor()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long eventId = eventId(session);
        if (eventId == null) return;
        Set<WebSocketSession> set = ROOMS.get(eventId);
        if (set != null) set.remove(session);
    }

    /** 本实例向某场次所有会话推送（Redis 订阅侧回调） */
    public void localBroadcast(Long eventId, DanmakuMessage msg) {
        Set<WebSocketSession> set = ROOMS.getOrDefault(eventId, Set.of());
        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.warn("弹幕序列化失败", e);
            return;
        }
        for (WebSocketSession s : set) {
            if (s.isOpen()) {
                try {
                    s.sendMessage(new TextMessage(json));
                } catch (IOException e) {
                    log.warn("弹幕推送失败 session={}", s.getId(), e);
                }
            }
        }
    }

    private Long eventId(WebSocketSession s) {
        Object v = s.getAttributes().get("eventId");
        try {
            return v == null ? null : Long.valueOf(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String error(String msg) {
        return "{\"type\":\"ERROR\",\"message\":\"" + msg + "\"}";
    }

    private void send(WebSocketSession s, String text) {
        try {
            s.sendMessage(new TextMessage(text));
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(WebSocketSession s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
