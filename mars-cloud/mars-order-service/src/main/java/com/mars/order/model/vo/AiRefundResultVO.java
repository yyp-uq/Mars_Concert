package com.mars.order.model.vo;

import lombok.Data;

/**
 * AI 客服发起退款后的结果视图。
 */
@Data
public class AiRefundResultVO {
    private String orderNo;
    private String status;
}
