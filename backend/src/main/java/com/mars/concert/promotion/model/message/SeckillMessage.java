package com.mars.concert.promotion.model.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀异步下单消息（Redis 预减成功后投递到 seckill.exchange）。
 * ticket 是前端轮询结果的凭证，同时充当建单幂等键。
 * 必须有无参构造，供 Jackson 反序列化。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage {
    private Long actId;
    private Long userId;
    private String ticket;
}
