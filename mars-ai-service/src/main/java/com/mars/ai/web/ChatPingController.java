package com.mars.ai.web;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 连通性探针：验证 Key/网络/模型是否全通 */
@RestController
@RequestMapping("/api/v1/ai")
public class ChatPingController {

    private final ChatClient chatClient;

    public ChatPingController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @GetMapping("/ping")
    public String ping(@RequestParam(defaultValue = "你好") String q) {
        try {
            return chatClient.prompt().user(q).call().content();
        } catch (Exception e) {
            // 大模型不可用（未配置 Key / 网络异常）时兜底，避免前端白屏
            return "小火星暂时开小差了，已为您转接人工客服（9:00-21:00）。请检查 DASHSCOPE_API_KEY 配置。";
        }
    }
}
