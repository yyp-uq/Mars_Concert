package com.mars.concert.order.controller;

import com.mars.concert.common.annotation.OperationLog;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.order.model.dto.CreateOrderDTO;
import com.mars.concert.order.model.dto.PricePreviewDTO;
import com.mars.concert.order.model.vo.OrderVO;
import com.mars.concert.order.service.OrderPrice;
import com.mars.concert.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "交易-订单")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "提交订单")
    @PostMapping
    public Result<String> create(@CurrentUser Long userId,
                                 @Valid @RequestBody CreateOrderDTO dto) {
        return Result.success(orderService.createOrder(userId, dto));
    }

    @Operation(summary = "选券试算（确认页展示优惠后金额）")
    @PostMapping("/price-preview")
    public Result<OrderPrice> pricePreview(@CurrentUser Long userId,
                                           @Valid @RequestBody PricePreviewDTO dto) {
        return Result.success(orderService.pricePreview(userId, dto));
    }

    @Operation(summary = "我的订单列表")
    @GetMapping
    public Result<PageResult<OrderVO>> page(@CurrentUser Long userId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") long pageNum,
                                             @RequestParam(defaultValue = "10") long pageSize) {
        return Result.success(orderService.pageMine(userId, status, pageNum, pageSize));
    }

    @Operation(summary = "订单详情")
    @GetMapping("/{orderNo}")
    public Result<OrderVO> detail(@CurrentUser Long userId, @PathVariable String orderNo) {
        return Result.success(orderService.detailMine(userId, orderNo));
    }

    @Operation(summary = "取消订单(待支付)")
    @OperationLog(module = "订单", action = "取消订单", bizIdSpel = "#orderNo")
    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@CurrentUser Long userId, @PathVariable String orderNo) {
        orderService.cancel(userId, orderNo);
        return Result.success();
    }
}
