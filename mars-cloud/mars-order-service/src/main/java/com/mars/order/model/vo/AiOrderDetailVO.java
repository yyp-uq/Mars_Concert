package com.mars.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 客服订单详情视图（第9卷）。
 */
@Data
public class AiOrderDetailVO {
    private String orderNo;
    private String eventTitle;
    private String status;
    private BigDecimal totalAmount;
    private BigDecimal payAmount;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private List<AiOrderItemVO> items;
}
