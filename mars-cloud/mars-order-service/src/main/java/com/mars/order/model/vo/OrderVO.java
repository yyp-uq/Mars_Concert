package com.mars.order.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderVO {
    private String orderNo;
    private Long eventId;
    private String eventTitle;
    private String status;
    private BigDecimal totalAmount;
    private BigDecimal serviceAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    private LocalDateTime createTime;
    private List<OrderItemVO> items;
}
