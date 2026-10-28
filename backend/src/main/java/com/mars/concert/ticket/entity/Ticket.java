package com.mars.concert.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 电子票（表 tk_ticket） */
@Data
@TableName("tk_ticket")
public class Ticket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ticketNo;
    private Long orderId;
    private Long orderItemId;
    private Long eventId;
    /** 站票为空 */
    private Long seatId;
    private Long viewerId;
    /** 64位随机、不可枚举 */
    private String qrToken;
    /** VALID/USED/REFUNDED */
    private String status;
    private LocalDateTime checkinTime;
    private String checkinDevice;
    private LocalDateTime createTime;
}
