package com.mars.ai.web;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 聊天请求体 */
@Data
public class ChatDTO {

    /** 前端生成的会话 ID（后端会再拼用户前缀做隔离） */
    @NotBlank(message = "会话ID不能为空")
    private String conversationId;

    @NotBlank(message = "消息不能为空")
    private String message;

    /** 退款二次确认 token，普通对话为 null */
    private String confirmToken;
}
