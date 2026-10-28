package com.mars.concert.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 消息模板（表 msg_template），content 支持 ${name} ${orderNo} 占位 */
@Data
@TableName("msg_template")
public class MsgTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** PAY_SUCCESS / REFUND_SUCCESS */
    private String code;
    /** EMAIL/SMS/INBOX */
    private String channel;
    private String title;
    private String content;
}
