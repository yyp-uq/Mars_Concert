package com.mars.concert.order.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 选券试算入参：订单确认页展示优惠后金额（手册 §5.1） */
@Data
public class PricePreviewDTO {
    @NotNull(message = "场次不能为空")
    private Long eventId;

    @NotEmpty(message = "购票明细不能为空")
    @Valid
    private List<OrderItemDTO> items;

    /** 用户券ID（pm_user_coupon.id），可空=不用券试算 */
    private Long userCouponId;
}
