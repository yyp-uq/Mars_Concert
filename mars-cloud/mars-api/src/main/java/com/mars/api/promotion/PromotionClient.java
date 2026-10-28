package com.mars.api.promotion;

import com.mars.api.promotion.dto.CouponTemplateDTO;
import com.mars.api.promotion.dto.MarkUsedDTO;
import com.mars.api.promotion.dto.UserCouponDTO;
import com.mars.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * 营销（优惠券）服务契约。promotion-service 在第8卷后续拆分，
 * 当前由 fallback 降级：不用券的下单主链路不受影响。
 */
@FeignClient(name = "mars-promotion-service", path = "/internal/promotion",
        fallbackFactory = PromotionClientFallback.class)
public interface PromotionClient {

    /** 校验券可用（归属/状态/有效期），不可用抛业务异常 */
    @GetMapping("/coupons/usable")
    Result<UserCouponDTO> checkUsable(@RequestParam("userId") Long userId,
                                      @RequestParam("userCouponId") Long userCouponId);

    /** 查券模板（范围/门槛/面额） */
    @GetMapping("/coupons/templates/{id}")
    Result<CouponTemplateDTO> getTemplate(@PathVariable("id") Long id);

    /** 核销券：affected=0 由提供方抛异常回滚整单 */
    @PostMapping("/coupons/mark-used")
    Result<Void> markUsed(@RequestBody MarkUsedDTO dto);

    /** 取消/超时关单按单号回退券（幂等） */
    @PostMapping("/coupons/refund-by-order")
    Result<Void> refundByOrderNo(@RequestParam("orderNo") String orderNo);
}
