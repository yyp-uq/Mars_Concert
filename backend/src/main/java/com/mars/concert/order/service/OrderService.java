package com.mars.concert.order.service;

import com.mars.concert.common.result.PageResult;
import com.mars.concert.order.enums.OrderEvent;
import com.mars.concert.order.model.dto.CreateOrderDTO;
import com.mars.concert.order.model.dto.PricePreviewDTO;
import com.mars.concert.order.model.vo.OrderExportRow;
import com.mars.concert.order.model.vo.OrderVO;

import java.util.List;

public interface OrderService {
    String createOrder(Long userId, CreateOrderDTO dto);

    void cancel(Long userId, String orderNo);

    PageResult<OrderVO> pageMine(Long userId, String status, long pageNum, long pageSize);

    OrderVO detailMine(Long userId, String orderNo);

    /** 状态机驱动迁移：按当前状态 + 事件算出目标状态并落库、写状态日志（支付/出票/退款复用） */
    void transit(Long orderId, OrderEvent event, String operator);

    /** 超时关单（幂等：仅 WAIT_PAY 才关），供延迟队列/兜底任务调用 */
    void closeIfTimeout(String orderNo);

    /** 查询已过期仍待支付的订单（兜底扫描用） */
    java.util.List<com.mars.concert.order.entity.Orders> listExpiredWaitPay(int limit);

    /** 第6卷：选券试算（订单确认页展示优惠后金额，不落库） */
    OrderPrice pricePreview(Long userId, PricePreviewDTO dto);

    /** 第6卷：后台订单导出（EasyExcel） */
    List<OrderExportRow> listForExport(String status);
}
