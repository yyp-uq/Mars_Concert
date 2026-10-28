package com.mars.order.controller;

import com.alibaba.excel.EasyExcel;
import com.mars.common.annotation.OperationLog;
import com.mars.order.model.vo.OrderExportRow;
import com.mars.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 后台-订单导出（手册 §10，需 order:export 权限） */
@Tag(name = "后台-订单导出")
@RestController
@RequestMapping("/api/admin/orders")
@RequiredArgsConstructor
public class AdminOrderExportController {

    private final OrderService orderService;

    @Operation(summary = "导出订单 xlsx")
    @OperationLog(module = "订单", action = "导出订单")
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       HttpServletResponse resp) throws IOException {
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("orders_" + System.currentTimeMillis() + ".xlsx",
                StandardCharsets.UTF_8);
        resp.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName);

        List<OrderExportRow> rows = orderService.listForExport(status);
        EasyExcel.write(resp.getOutputStream(), OrderExportRow.class).sheet("订单").doWrite(rows);
    }
}
