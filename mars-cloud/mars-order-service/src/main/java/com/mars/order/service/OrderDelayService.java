package com.mars.order.service;

import com.mars.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 订单超时延迟消息投递（Redisson 延迟队列）：
 * 先把元素放 zset（按到期时间排序），后台线程把到期元素搬到目标 BlockingQueue，
 * 消费者 take() 阻塞获取，无需自己轮询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDelayService {

    private final RedissonClient redisson;

    /** 下单成功后投递：delaySeconds 后触发关单检查 */
    public void sendCloseCheck(String orderNo, long delaySeconds) {
        RBlockingQueue<String> blocking = redisson.getBlockingQueue(RedisKeys.ORDER_TIMEOUT_QUEUE);
        RDelayedQueue<String> delayed = redisson.getDelayedQueue(blocking);
        delayed.offer(orderNo, delaySeconds, TimeUnit.SECONDS);
        log.debug("投递延迟关单消息 orderNo={} delay={}s", orderNo, delaySeconds);
    }
}
