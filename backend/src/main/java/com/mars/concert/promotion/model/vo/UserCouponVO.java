package com.mars.concert.promotion.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 我的券列表项：用户券 + 模板信息组装返回 */
@Data
public class UserCouponVO {
    private Long id;
    private Long templateId;
    private String name;
    /** FULL_CUT / DISCOUNT / CASH */
    private String type;
    private BigDecimal faceValue;
    private BigDecimal threshold;
    private String scope;
    private Long scopeId;
    /** UNUSED / USED / EXPIRED */
    private String status;
    private String orderNo;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
}
