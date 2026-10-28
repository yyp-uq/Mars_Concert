package com.mars.concert.ticket.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 我的电子票（含二维码 token，仅本人可见） */
@Data
public class TicketVO {
    private String ticketNo;
    private Long eventId;
    private String eventTitle;
    private Long seatId;
    private String qrToken;
    private String status;
    private LocalDateTime checkinTime;
}
