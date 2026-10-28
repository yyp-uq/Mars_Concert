package com.mars.concert.promotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动，对应 pm_seckill_activity 表。
 * 库存开售前预热到 Redis(mars:seckill:{actId}:stock)，抢购走 Lua 原子扣减，
 * DB total_stock 只做对账参考，不在抢购链路直接扣。
 */
@Data
@TableName("pm_seckill_activity")
public class SeckillActivity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long eventId;
    private Long tierId;
    private BigDecimal seckillPrice;
    private Integer totalStock;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer perUserLimit;
    /** 1启用 0停用 */
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted;
}
