package com.mars.concert.order.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderDTO {
    @NotNull(message = "场次不能为空")
    private Long eventId;

    /** 站票/按票档下单明细；选座下单时由 seatIds 推导，可不传 */
    @Valid
    private List<OrderItemDTO> items;

    /** 选座下单：座位ID列表（第4卷）。与 items 二选一 */
    private List<Long> seatIds;

    private String idempotentKey;
    private String remark;

    /** 第6卷：下单使用的用户券ID（pm_user_coupon.id），可空 */
    private Long userCouponId;
}
