package com.mars.api.promotion.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户券（服务间契约，字段是 pm_user_coupon 的子集） */
@Data
public class UserCouponDTO {
    private Long id;
    private Long templateId;
    private Long userId;
    /** UNUSED / USED / EXPIRED */
    private String status;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
}
