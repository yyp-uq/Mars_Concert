package com.mars.concert.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 消息记录（表 msg_record），站内信 INBOX 可被前端「我的消息」读取 */
@Data
@TableName("msg_record")
public class MsgRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String channel;
    private String templateCode;
    private String target;
    private String content;
    /** PENDING/SENT/FAIL */
    private String status;
    private Integer retryCount;
    private String remark;
    private LocalDateTime createTime;
}
