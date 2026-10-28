package com.mars.ai.tools.dto;

/**
 * 退款工具返回。
 * needConfirm=true 时仅返回确认提示与一次性 token，不执行任何资金操作。
 */
public record RefundResultVO(boolean needConfirm,
                             String orderNo,
                             String message,
                             String confirmToken,
                             String status) {
}
