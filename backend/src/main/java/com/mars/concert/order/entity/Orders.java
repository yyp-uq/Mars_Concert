package com.mars.concert.order.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ord_order")
public class Orders {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private Long eventId;
    private BigDecimal totalAmount;
    private BigDecimal serviceAmount;
    private BigDecimal discountAmount;
    private BigDecimal payAmount;
    private String status;
    private String idempotentKey;
    private String remark;
    private LocalDateTime expireTime;
    private LocalDateTime payTime;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
    @Version
    private Integer version;
}
