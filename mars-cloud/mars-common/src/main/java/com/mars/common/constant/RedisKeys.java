package com.mars.common.constant;

public interface RedisKeys {
    String SMS_CODE = "mars:sms:";             // +phone
    String SMS_DAY_CNT = "mars:sms:cnt:";      // +phone
    String LOGIN_FAIL = "mars:login:fail:";    // +phone
    String TOKEN_ACCESS = "mars:token:access:";
    String TOKEN_REFRESH = "mars:token:refresh:";

    // ---------- 第4卷：选座与库存 ----------
    String EVENT_SEAT_HASH = "mars:event:%s:seat";              // Hash field=seatId value=F/L/S
    String SEAT_LOCK = "mars:seat:lock:%s:%s";                  // eventId:seatId
    String EVENT_TIER_STOCK = "mars:event:%s:tier:%s:stock";    // 站票/票档余票
    String SEAT_LOCK_ORDER = "mars:seat:order:%s";              // orderNo -> 锁定座位集合(用于关单)
    String EVENT_GUARD_LOCK = "mars:lock:event:%s";             // 同场次选座串行化保护锁
    String ORDER_TIMEOUT_QUEUE = "mars:order:timeout:block";    // 订单超时关单延迟队列

    // ---------- 第5卷：电子票核销防重 ----------
    String TICKET_USED = "mars:ticket:used:";                   // +qrToken 已核销标记(SETNX原子防重)

    // ---------- 第6卷：弹幕 ----------
    String DANMAKU_RATE = "mars:rate:danmaku:";                 // +userId 每人每秒1条限流
    String DANMAKU_CHANNEL = "mars:channel:danmaku:";           // +eventId Pub/Sub 集群广播频道

    // ---------- 第6卷：优惠券 ----------
    String COUPON_USER = "mars:coupon:%s:user:%s";              // tplId:userId 一人一券标记
    String COUPON_STOCK = "mars:coupon:%s:stock";               // tplId 券库存(预热写入)

    // ---------- 第6卷：积分签到 ----------
    String SIGN_DAY = "mars:sign:%s:%s";                        // userId:yyyy-MM-dd 当日签到标记

    // ---------- 第6卷：秒杀 ----------
    String SECKILL_STOCK = "mars:seckill:%s:stock";             // actId 活动余量(Lua原子扣减)
    String SECKILL_USER = "mars:seckill:%s:user:%s";            // actId:userId 已抢标记
    String SECKILL_RESULT = "mars:seckill:result:%s";           // ticket -> QUEUED/订单号/FAIL

    // ---------- 第6卷：搜索热词 ----------
    String HOT_SONGS = "mars:hot:songs";                        // ZSet member=关键词 score=次数
}
