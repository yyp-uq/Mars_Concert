package com.mars.concert.promotion.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分流水，对应 pm_point_log 表。
 * uk_type_biz(type, biz_no) 唯一索引：同业务同类型只记一次，消费幂等兜底。
 */
@Data
@TableName("pm_point_log")
public class PointLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    /** 正为得、负为用 */
    private Integer changeValue;
    /** SIGN / ORDER_REFUND / ORDER_EARN / EXCHANGE */
    private String type;
    /** 业务号（订单号 / 签到日期） */
    private String bizNo;
    /** 变动后余额 */
    private Integer balanceAfter;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
