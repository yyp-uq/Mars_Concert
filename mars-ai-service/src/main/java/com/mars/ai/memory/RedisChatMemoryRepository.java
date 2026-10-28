package com.mars.ai.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis 的会话记忆仓库。
 * key = mars:ai:chat:{conversationId}，value = 消息 JSON 数组，TTL 12 小时。
 * 序列化统一使用 Jackson（严禁 fastjson）。
 */
@Component
public class RedisChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryRepository.class);

    private static final String PREFIX = "mars:ai:chat:";
    private static final Duration TTL = Duration.ofHours(12);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys == null || keys.isEmpty()) return List.of();
        return keys.stream().map(k -> k.substring(PREFIX.length())).toList();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        List<Message> messages = new ArrayList<>();
        String json = redis.opsForValue().get(PREFIX + conversationId);
        if (!StringUtils.hasText(json)) return messages;
        try {
            List<StoredMessage> stored = objectMapper.readValue(json, new TypeReference<>() {
            });
            for (StoredMessage s : stored) {
                if (s.type() == null || !StringUtils.hasText(s.text())) continue;
                switch (s.type()) {
                    case "USER" -> messages.add(new UserMessage(s.text()));
                    case "ASSISTANT" -> messages.add(new AssistantMessage(s.text()));
                    case "SYSTEM" -> messages.add(new SystemMessage(s.text()));
                    default -> {
                        // TOOL 等类型不入记忆，避免回放工具报文干扰模型
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取会话记忆失败 conversationId={}: {}", conversationId, e.getMessage());
        }
        return messages;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<StoredMessage> stored = messages.stream()
                .filter(m -> m.getMessageType() != null && StringUtils.hasText(m.getText()))
                .map(m -> new StoredMessage(m.getMessageType().name(), m.getText()))
                .toList();
        try {
            redis.opsForValue().set(PREFIX + conversationId,
                    objectMapper.writeValueAsString(stored), TTL);
        } catch (Exception e) {
            log.warn("写入会话记忆失败 conversationId={}: {}", conversationId, e.getMessage());
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        redis.delete(PREFIX + conversationId);
    }

    /** Redis 中持久化的精简消息结构 */
    private record StoredMessage(String type, String text) {
    }
}
