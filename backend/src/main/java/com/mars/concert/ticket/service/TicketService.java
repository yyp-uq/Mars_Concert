package com.mars.concert.ticket.service;

import com.mars.concert.ticket.entity.Ticket;
import com.mars.concert.ticket.model.vo.TicketVO;

import java.util.List;

public interface TicketService {

    /** 支付成功出票（幂等：一张明细一张票） */
    void issueTickets(String orderNo);

    /** 本人票据（必须属于本人订单） */
    Ticket getOwnedTicket(Long userId, String ticketNo);

    /** 我的票列表 */
    List<TicketVO> listMine(Long userId);

    /** 扫码核销（Redis 原子防重 + DB 条件更新兜底） */
    void checkin(String token, String device);

    /** 退款使票失效 */
    void markRefunded(Long orderId);
}
