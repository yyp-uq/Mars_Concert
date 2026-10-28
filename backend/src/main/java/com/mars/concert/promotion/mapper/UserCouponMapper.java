package com.mars.concert.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mars.concert.promotion.entity.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 下单核销券：条件更新保证一张券只能被一单使用。
     * affected=0 说明券不存在/不属于该用户/已被并发核销，调用方抛异常回滚。
     */
    @Update("""
        UPDATE pm_user_coupon
           SET status = 'USED', order_no = #{orderNo}
         WHERE id = #{id} AND user_id = #{userId} AND status = 'UNUSED'
        """)
    int useCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderNo") String orderNo);

    /**
     * 取消/退款回退券：按订单号反查，置回 UNUSED 并清空订单号。
     * 幂等：已是 UNUSED 的不会再被更新（重复回退 affected=0 无副作用）。
     */
    @Update("""
        UPDATE pm_user_coupon
           SET status = 'UNUSED', order_no = NULL
         WHERE order_no = #{orderNo} AND status = 'USED'
        """)
    int refundByOrderNo(@Param("orderNo") String orderNo);
}
