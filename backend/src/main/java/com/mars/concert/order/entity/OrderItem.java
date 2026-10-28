package com.mars.concert.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ord_order_item")
public class OrderItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long eventId;
    private Long tierId;
    private Long viewerId;
    /** 座位ID（选座订单，第4卷新增列） */
    private Long seatId;
    private BigDecimal unitPrice;
    private Integer quantity;
    private BigDecimal subTotal;
    private LocalDateTime createTime;
}
