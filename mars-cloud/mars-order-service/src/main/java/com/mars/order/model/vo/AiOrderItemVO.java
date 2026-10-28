package com.mars.order.model.vo;

import lombok.Data;

/**
 * AI 客服订单明细行视图。
 */
@Data
public class AiOrderItemVO {
    private String tierName;
    private Integer quantity;
    private Long seatId;
}
