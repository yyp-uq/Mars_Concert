package com.mars.show.inventory.model.dto;

import lombok.Data;

/**
 * 退款回补库存的明细项（第8卷：订单域数据不再跨服务直查，
 * 由调用方[支付服务]把订单明细组装成本 DTO 传进来）。
 */
@Data
public class PaidReleaseItemDTO {
    /** 座位ID（坐票）；站票为 null */
    private Long seatId;
    private Long tierId;
    private Integer quantity;
}
