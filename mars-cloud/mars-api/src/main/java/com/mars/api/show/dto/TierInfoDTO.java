package com.mars.api.show.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 场次票档信息：event_tier 主键 + 服务端真实价格 + 票档名 */
@Data
public class TierInfoDTO {
    /** show_event_tier.id（扣减库存用它定位） */
    private Long id;
    private Long eventId;
    private Long tierId;
    private String tierName;
    private BigDecimal price;
}
