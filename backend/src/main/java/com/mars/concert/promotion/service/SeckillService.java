package com.mars.concert.promotion.service;

import com.mars.concert.promotion.model.message.SeckillMessage;

public interface SeckillService {

    /**
     * 执行秒杀：Lua 原子判重+预减 Redis 库存，成功后投递 MQ 异步建单。
     * 立即返回抢购凭证 ticket，前端拿它轮询结果。
     */
    String execute(Long userId, Long actId);

    /** 轮询秒杀结果：QUEUED / 订单号 / FAIL / EXPIRED */
    String result(String ticket);

    /**
     * 消费端异步建单：幂等键 SECKILL-{ticket}，扣 DB 库存 + 写 WAIT_PAY 订单。
     * 抛异常时由消费者补偿 Redis 库存与用户标记。
     */
    String createSeckillOrder(SeckillMessage msg);
}
