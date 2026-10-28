package com.mars.order.service;

import com.mars.common.result.PageResult;
import com.mars.order.enums.OrderEvent;
import com.mars.order.model.dto.CreateOrderDTO;
import com.mars.order.model.dto.PricePreviewDTO;
import com.mars.order.model.vo.AiOrderBriefVO;
import com.mars.order.model.vo.AiOrderDetailVO;
import com.mars.order.model.vo.AiRefundResultVO;
import com.mars.order.model.vo.OrderExportRow;
import com.mars.order.model.vo.OrderVO;

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
    java.util.List<com.mars.order.entity.Orders> listExpiredWaitPay(int limit);

    /** 第6卷：选券试算（订单确认页展示优惠后金额，不落库） */
    OrderPrice pricePreview(Long userId, PricePreviewDTO dto);

    /** 第6卷：后台订单导出（EasyExcel） */
    List<OrderExportRow> listForExport(String status);

    /** 第9卷：AI 客服查我的订单（字段精简，最多20条） */
    List<AiOrderBriefVO> listAiBrief(Long userId, String status);

    /** 第9卷：AI 客服查订单详情（强制按 userId 校验归属） */
    AiOrderDetailVO detailForAi(Long userId, String orderNo);

    /** 第9卷：AI 客服发起退款（校验归属与状态，走状态机迁移并记日志） */
    AiRefundResultVO applyRefundForAi(Long userId, String orderNo);
}
