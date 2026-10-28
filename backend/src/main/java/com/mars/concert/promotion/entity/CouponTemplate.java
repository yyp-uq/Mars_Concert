package com.mars.concert.promotion.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券模板，对应 pm_coupon_template 表。
 * type: FULL_CUT 满减 / DISCOUNT 折扣 / CASH 立减；
 * scope: ALL 全场 / EVENT 指定场次 / TIER 指定票档（配合 scopeId）。
 */
@Data
@TableName("pm_coupon_template")
public class CouponTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** FULL_CUT满减 / DISCOUNT折扣 / CASH立减 */
    private String type;
    /** 面额或折扣(如 8.5 表示 8.5 折) */
    private BigDecimal faceValue;
    /** 使用门槛(满 X 元可用，按票价合计判断) */
    private BigDecimal threshold;
    /** 发行总量（预热到 Redis，领取时原子扣减） */
    private Integer totalCount;
    /** 每人限领张数（学习期按 1 实现，一人一券） */
    private Integer perUserLimit;
    /** ALL / EVENT / TIER */
    private String scope;
    /** scope 为 EVENT/TIER 时对应的 id */
    private Long scopeId;
    /** 领取后 N 天有效 */
    private Integer validDays;
    /** 1上架 0下架 */
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted;
}
