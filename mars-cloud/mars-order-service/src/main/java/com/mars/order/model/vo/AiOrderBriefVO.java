package com.mars.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 客服订单简要视图（第9卷）：字段精简，降低 token 消耗。
 */
@Data
public class AiOrderBriefVO {
    private String orderNo;
    private String eventTitle;
    private String status;
    private BigDecimal payAmount;
    private LocalDateTime createTime;
}
