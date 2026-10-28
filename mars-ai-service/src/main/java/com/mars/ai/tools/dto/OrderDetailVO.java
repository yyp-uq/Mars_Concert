package com.mars.ai.tools.dto;

import java.util.List;

/** 订单详情（含明细） */
public record OrderDetailVO(String orderNo,
                            String eventTitle,
                            String status,
                            String totalAmount,
                            String payAmount,
                            String createTime,
                            String payTime,
                            List<OrderItemVO> items) {
}
