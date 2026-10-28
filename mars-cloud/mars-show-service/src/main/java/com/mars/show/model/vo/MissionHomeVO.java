package com.mars.show.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页聚合返回：mission.html 用。
 */
@Data
public class MissionHomeVO {
    private LocalDateTime nextEventTime;      // 最近一场场次开始时间（倒计时目标）
    private List<EventBrief> events;          // 场次简要列表
    private VenueBrief venue;                 // 场馆
    private String priceRange;                // "¥380 - ¥1280"
    private String guest;                     // 嘉宾（取第一场的嘉宾）

    @Data
    public static class EventBrief {
        private Long id;
        private String title;
        private LocalDateTime startTime;
        private String city;
        private String guest;
    }

    @Data
    public static class VenueBrief {
        private String name;
        private String city;
        private String address;
    }
}
