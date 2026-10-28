package com.mars.order.job;

import com.mars.api.show.ShowClient;
import com.mars.common.constant.RedisKeys;
import com.mars.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 订单超时关单消费者：阻塞取 Redisson 延迟队列中的到期订单号，
 * 关单（幂等：仅 WAIT_PAY 才关）并释放预占座位。
 * 第8卷：座位释放经 Feign 远程调用演出服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer implements ApplicationRunner {

    private final RedissonClient redisson;
    private final OrderService orderService;
    private final ShowClient showClient;

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(() -> {
            RBlockingQueue<String> q = redisson.getBlockingQueue(RedisKeys.ORDER_TIMEOUT_QUEUE);
            redisson.getDelayedQueue(q);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String orderNo = q.take();   // 阻塞直到有到期订单
                    orderService.closeIfTimeout(orderNo);
                    showClient.releaseByOrderNo(orderNo);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("关单处理异常", e);
                }
            }
        }, "order-timeout-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("订单超时关单消费者已启动");
    }
}
