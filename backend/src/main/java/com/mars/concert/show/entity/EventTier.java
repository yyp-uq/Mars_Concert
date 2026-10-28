package com.mars.concert.show.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("show_event_tier")
public class EventTier {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long eventId;
    private Long tierId;
    private BigDecimal price;
    private Integer totalStock;
    private Integer lockedStock;
    private Integer soldStock;
    private Integer status;
    @Version
    private Integer version;
}
