package com.mars.concert.order.model.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;

/** 订单导出行（EasyExcel 注解定义表头，手册 §10） */
@Data
public class OrderExportRow {
    @ExcelProperty("订单号")
    private String orderNo;
    @ExcelProperty("用户ID")
    private Long userId;
    @ExcelProperty("场次")
    private String eventTitle;
    @ExcelProperty("应付金额")
    private BigDecimal payAmount;
    @ExcelProperty("状态")
    private String status;
    @ExcelProperty("创建时间")
    private String createTime;
}
