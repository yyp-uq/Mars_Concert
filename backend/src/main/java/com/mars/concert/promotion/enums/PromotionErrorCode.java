package com.mars.concert.promotion.enums;

import com.mars.concert.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 营销域错误码：70xx 优惠券 / 71xx 积分 / 72xx 秒杀 */
@Getter
@AllArgsConstructor
public enum PromotionErrorCode implements ErrorCode {
    COUPON_NOT_FOUND(7001, "券不存在或已下架"),
    COUPON_RECEIVED(7002, "您已领取过该券"),
    COUPON_SOLD_OUT(7003, "券已被领完"),
    COUPON_NOT_USABLE(7004, "券不可用（不存在/已用/过期/未达门槛）"),

    POINT_CONFLICT(7101, "积分更新冲突，请重试"),
    SIGN_REPEAT(7102, "今日已签到"),

    SECKILL_SOLD_OUT(7201, "已售罄"),
    SECKILL_BOUGHT(7202, "您已抢购过"),
    SECKILL_NOT_START(7203, "活动未开始"),
    SECKILL_ENDED(7204, "活动已结束"),
    SECKILL_NOT_FOUND(7205, "秒杀活动不存在或已停用");

    private final int code;
    private final String message;
}
