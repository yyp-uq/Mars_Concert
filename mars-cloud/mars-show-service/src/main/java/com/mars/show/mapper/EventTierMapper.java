package com.mars.show.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.show.entity.EventTier;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface EventTierMapper extends BaseMapper<EventTier> {

    /**
     * 条件更新扣库存：WHERE total - sold - locked >= qty 保证原子防超卖。
     * affected=0 说明余票不足。
     */
    @Update("""
        UPDATE show_event_tier
           SET sold_stock = sold_stock + #{qty}, version = version + 1
         WHERE id = #{id} AND total_stock - sold_stock - locked_stock >= #{qty}
        """)
    int deductStock(@Param("id") Long id, @Param("qty") int qty);

    /** 取消订单回补库存（按 eventId + tierId 定位） */
    @Update("""
        UPDATE show_event_tier
           SET sold_stock = sold_stock - #{qty}, version = version + 1
         WHERE event_id = #{eventId} AND tier_id = #{tierId} AND sold_stock >= #{qty}
        """)
    int addStock(@Param("eventId") Long eventId, @Param("tierId") Long tierId, @Param("qty") int qty);
}
