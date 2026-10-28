package com.mars.ai.web;

import com.mars.ai.service.CustomerSupportService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE 流式对话入口。身份来自网关下推的 X-User-Id，缺失直接 401。
 */
@RestController
@RequestMapping("/api/v1/ai")
public class ChatSseController {

    private final CustomerSupportService supportService;

    public ChatSseController(CustomerSupportService supportService) {
        this.supportService = supportService;
    }

    /**
     * 成功时以 text/event-stream 返回 SseEmitter；未登录时改为 JSON 401，
     * 因此不在注解上固定 produces，避免 401 响应被内容协商拦截。
     */
    @PostMapping("/chat/stream")
    public ResponseEntity<?> chatStream(@RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
                                        @Valid @RequestBody ChatDTO dto) {
        if (!StringUtils.hasText(userIdHeader)) return unauthorized();
        Long userId;
        try {
            userId = Long.valueOf(userIdHeader);
        } catch (NumberFormatException e) {
            return unauthorized();
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        supportService.streamChat(userId, dto.getConversationId(), dto.getMessage(),
                dto.getConfirmToken(), emitter);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .body(emitter);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", 1401, "message", "未登录"));
    }
}
