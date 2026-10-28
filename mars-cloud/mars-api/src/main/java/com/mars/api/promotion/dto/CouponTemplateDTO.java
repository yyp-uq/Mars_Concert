package com.mars.api.promotion.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 券模板（服务间契约，价格引擎判定范围/门槛/面额用） */
@Data
public class CouponTemplateDTO {
    private Long id;
    /** FULL_CUT满减 / DISCOUNT折扣 / CASH立减 */
    private String type;
    private BigDecimal faceValue;
    private BigDecimal threshold;
    /** ALL / EVENT / TIER */
    private String scope;
    private Long scopeId;
}
