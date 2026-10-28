package com.mars.concert.promotion.service;

import com.mars.concert.promotion.entity.CouponTemplate;
import com.mars.concert.promotion.entity.UserCoupon;
import com.mars.concert.promotion.model.vo.UserCouponVO;

import java.util.List;

public interface CouponService {

    /** 领券：Redis 原子校验一人一券 + 库存扣减，DB 唯一索引兜底 */
    void receive(Long userId, Long tplId);

    /** 我的券列表（status 可空：UNUSED/USED/EXPIRED） */
    List<UserCouponVO> myCoupons(Long userId, String status);

    /**
     * 订单侧调用：校验券归属本人、UNUSED、在有效期内，返回券实体。
     * 任一不满足抛 COUPON_NOT_USABLE。
     */
    UserCoupon checkUsable(Long userId, Long userCouponId);

    /** 取券模板（订单算价用），不存在抛 COUPON_NOT_FOUND */
    CouponTemplate getTemplate(Long templateId);

    /** 下单核销：条件更新，affected=0 抛 COUPON_NOT_USABLE（调用方在事务内，整单回滚） */
    void markUsed(Long userCouponId, Long userId, String orderNo);

    /** 取消/退款回退券：按订单号反查置回 UNUSED（幂等） */
    void refundByOrderNo(String orderNo);
}
