package com.mars.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.ai.tools.ConfirmCollector;
import com.mars.ai.tools.OrderTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 智能客服编排：会话隔离 + 多轮记忆 + RAG + 工具调用 + SSE 流式 + 退款二次确认。
 */
@Service
public class CustomerSupportService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSupportService.class);

    private static final String FALLBACK = "小火星开小差了，已为您转接人工客服（9:00-21:00）";
    private static final String REFUND_CONFIRM_PREFIX = "mars:ai:refund:confirm:";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final QuestionAnswerAdvisor questionAnswerAdvisor;
    private final OrderTools orderTools;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CustomerSupportService(ChatClient chatClient,
                                 ChatMemory chatMemory,
                                 QuestionAnswerAdvisor questionAnswerAdvisor,
                                 OrderTools orderTools,
                                 StringRedisTemplate redis,
                                 ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.questionAnswerAdvisor = questionAnswerAdvisor;
        this.orderTools = orderTools;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 流式对话。
     *
     * @param userId       网关下推的登录用户（绝不信任前端传值）
     * @param rawConvId    前端会话 ID，后端强制拼接用户前缀做隔离
     * @param userMsg      本轮用户输入
     * @param confirmToken 退款二次确认 token（普通对话为 null）
     * @param emitter      SSE 输出（delta / need_confirm / error）
     */
    public void streamChat(Long userId, String rawConvId, String userMsg,
                           String confirmToken, SseEmitter emitter) {
        String convId = "u" + userId + ":" +
                (StringUtils.hasText(rawConvId) ? rawConvId : UUID.randomUUID().toString());

        boolean refundConfirmed = validateRefundConfirm(userId, confirmToken);
        ConfirmCollector collector = new ConfirmCollector();

        Map<String, Object> toolContext = new HashMap<>();
        toolContext.put("userId", userId);
        toolContext.put("refundConfirmed", refundConfirmed);
        toolContext.put("confirmCollector", collector);

        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(convId)
                .build();

        Flux<String> flux = chatClient.prompt()
                .user(userMsg)
                .advisors(memoryAdvisor, questionAnswerAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .tools(orderTools)
                .toolContext(toolContext)
                .stream()
                .content();

        flux.subscribe(
                token -> sendQuietly(emitter, SseEmitter.event().name("delta").data(token)),
                error -> {
                    log.error("AI 流式对话失败 userId={} convId={}", userId, convId, error);
                    sendQuietly(emitter, SseEmitter.event().name("error").data(FALLBACK));
                    sendNeedConfirm(emitter, collector);
                    emitter.complete();
                },
                () -> {
                    sendNeedConfirm(emitter, collector);
                    emitter.complete();
                }
        );
    }

    /** 意图分类（结构化输出）：用于路由人工/机器人 */
    public SupportIntent classifyIntent(String userMessage) {
        return chatClient.prompt()
                .system("""
                        你是「火星演唱会」客服的意图分类器。请判断用户消息：
                        category 取值：order 订单/ ticket 购票/ refund 退款/ entry 入场/ setlist 歌单/ other 其他；
                        orderNo 为用户提到的订单号，没有则填空字符串；
                        needHuman 表示是否需要人工客服；urgency 为 0~1 的紧急度。
                        """)
                .user(userMessage)
                .call()
                .entity(SupportIntent.class);
    }

    /** 客服意图结构（手册 §10.1） */
    public record SupportIntent(String category, String orderNo, boolean needHuman, double urgency) {
    }

    /**
     * 校验一次性退款确认 token：按用户维度扫描待确认记录，命中即删除（一次性）。
     */
    private boolean validateRefundConfirm(Long userId, String confirmToken) {
        if (!StringUtils.hasText(confirmToken)) return false;
        Set<String> keys = redis.keys(REFUND_CONFIRM_PREFIX + userId + ":*");
        if (keys == null || keys.isEmpty()) return false;
        for (String key : keys) {
            String saved = redis.opsForValue().get(key);
            if (confirmToken.equals(saved)) {
                redis.delete(key);
                return true;
            }
        }
        return false;
    }

    /** 工具生成了确认 token 时，流结束前补发 need_confirm 事件给前端弹窗 */
    private void sendNeedConfirm(SseEmitter emitter, ConfirmCollector collector) {
        if (collector.getConfirmToken() == null) return;
        try {
            String data = objectMapper.writeValueAsString(Map.of(
                    "orderNo", collector.getOrderNo(),
                    "token", collector.getConfirmToken(),
                    "message", "退款需要您明确确认"));
            sendQuietly(emitter, SseEmitter.event().name("need_confirm").data(data));
        } catch (Exception e) {
            log.warn("发送 need_confirm 事件失败: {}", e.getMessage());
        }
    }

    private void sendQuietly(SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
        try {
            emitter.send(builder);
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE 发送终止（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
