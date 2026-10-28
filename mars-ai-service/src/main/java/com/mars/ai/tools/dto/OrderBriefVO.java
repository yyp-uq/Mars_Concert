package com.mars.ai.tools.dto;

/** 订单简要信息（给模型看的最小字段集，节省 token） */
public record OrderBriefVO(String orderNo,
                           String eventTitle,
                           String status,
                           String payAmount,
                           String createTime) {
}
