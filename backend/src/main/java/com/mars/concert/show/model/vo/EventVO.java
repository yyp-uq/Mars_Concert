package com.mars.concert.show.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class EventVO {
    private Long id;
    private String title;
    private String sessionType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String guest;
    private Integer status;
    private Integer limitPerOrder;
    private String venueName;
    private String city;
    private List<TierStockVO> tiers;
}
