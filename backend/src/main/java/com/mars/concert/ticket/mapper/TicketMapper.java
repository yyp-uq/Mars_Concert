package com.mars.concert.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.concert.ticket.entity.Ticket;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TicketMapper extends BaseMapper<Ticket> {

    /** 同一订单明细已生成的票数（出票幂等判断用） */
    @Select("SELECT COUNT(*) FROM tk_ticket WHERE order_item_id = #{orderItemId}")
    Long countByOrderItem(@Param("orderItemId") Long orderItemId);
}
