package com.mars.concert.notify.service;

public interface NotifyService {

    /** 支付成功通知：渲染模板 → INBOX 写记录 / EMAIL 发送(降级) / SMS mock */
    void sendOnPaySuccess(Long userId, String orderNo);
}
