package com.mars.concert.common.mq.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 本地消息表：与业务数据同事务写 PENDING，事务提交后再发 MQ，保证「改状态」与「发消息」最终一致。
 */
@Data
@TableName("sys_local_message")
public class LocalMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 业务号(订单号) */
    private String bizNo;
    private String exchange;
    private String routingKey;
    /** 消息体JSON */
    private String payload;
    /** PENDING/SENT */
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetry;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
