package com.mars.concert.common.job;

import com.mars.concert.order.entity.Orders;
import com.mars.concert.order.service.OrderService;
import com.mars.concert.show.inventory.service.SeatInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时兜底关单：延迟消息可能因应用宕机、Redis 重启丢失，
 * 独立扫描任务每分钟兜底一次。实时通道（延迟队列）追求快，兜底通道（定时扫描）追求不丢。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSweepJob {

    private final OrderService orderService;
    private final SeatInventoryService seatInventoryService;

    /** 每分钟扫描一次：过期时间已到、仍待支付的订单 */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        List<Orders> timeout = orderService.listExpiredWaitPay(50);
        for (Orders o : timeout) {
            try {
                orderService.closeIfTimeout(o.getOrderNo());
                seatInventoryService.releaseByOrderNo(o.getOrderNo());
            } catch (Exception e) {
                log.error("兜底关单失败 {}", o.getOrderNo(), e);
            }
        }
    }
}
