package com.mars.concert.community.model.dto;

import lombok.Data;

/**
 * 弹幕发送请求体（前端 WebSocket 文本帧 JSON）。
 * 必须有无参构造，供 Jackson 反序列化。
 */
@Data
public class DanmakuDTO {
    /** 展示昵称 */
    private String nickname;
    /** 弹幕内容（入库/广播前过敏感词） */
    private String content;
    /** 颜色，如 #F4A261 */
    private String color;
}
