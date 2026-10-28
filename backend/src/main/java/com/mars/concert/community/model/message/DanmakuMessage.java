package com.mars.concert.community.model.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 广播给各实例/前端的弹幕消息。
 * 必须有无参构造，供 Jackson 反序列化（Redis Pub/Sub 订阅侧）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DanmakuMessage {
    private Long userId;
    private String nickname;
    /** 已过敏感词过滤的净内容 */
    private String content;
    private String color;
    private LocalDateTime sendTime;

    public DanmakuMessage(Long userId, String nickname, String content, String color) {
        this.userId = userId;
        this.nickname = nickname;
        this.content = content;
        this.color = color;
        this.sendTime = LocalDateTime.now();
    }
}
