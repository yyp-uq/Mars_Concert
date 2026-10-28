package com.mars.concert.promotion.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

/**
 * 积分账户，对应 pm_point_account 表（主键即 userId）。
 * @Version 乐观锁：并发 earn/spend 时 MyBatis-Plus 自动带 version 条件更新，
 * affected=0 说明冲突，抛异常让调用方重试，防并发超扣。
 */
@Data
@TableName("pm_point_account")
public class PointAccount {
    @TableId(type = IdType.INPUT)
    private Long userId;
    private Integer balance;
    @Version
    private Integer version;
}
