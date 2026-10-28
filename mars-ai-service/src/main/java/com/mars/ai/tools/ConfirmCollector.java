package com.mars.ai.tools;

/**
 * 一次对话内共享的退款确认信息收集器。
 * 工具执行时把 token 写入这里，Service 在流结束时据此给前端发 need_confirm 事件。
 */
public class ConfirmCollector {

    private volatile String orderNo;
    private volatile String confirmToken;

    public void set(String orderNo, String confirmToken) {
        this.orderNo = orderNo;
        this.confirmToken = confirmToken;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getConfirmToken() {
        return confirmToken;
    }
}
