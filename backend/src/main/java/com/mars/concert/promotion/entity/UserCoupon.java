package com.mars.concert.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldFill;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户已领取的券，对应 pm_user_coupon 表。
 * status: UNUSED 未用 / USED 已核销 / EXPIRED 已过期。
 * uk_tpl_user(template_id, user_id) 唯一索引做一人一券的 DB 兜底。
 */
@Data
@TableName("pm_user_coupon")
public class UserCoupon {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long templateId;
    private Long userId;
    /** UNUSED / USED / EXPIRED */
    private String status;
    /** 核销订单号（取消/退款回退时按此反查并清空） */
    private String orderNo;
    private LocalDateTime validStart;
    private LocalDateTime validEnd;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
