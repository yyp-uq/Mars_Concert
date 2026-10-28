package com.mars.api.promotion;

import com.mars.api.promotion.dto.CouponTemplateDTO;
import com.mars.api.promotion.dto.MarkUsedDTO;
import com.mars.api.promotion.dto.UserCouponDTO;
import com.mars.common.exception.BusinessException;
import com.mars.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * 营销服务降级：promotion-service 未上线/宕机时，
 * 用券相关的试算与下单给出友好提示；券回退记录日志后成功（非核心链路）。
 */
@Slf4j
@Component
public class PromotionClientFallback implements FallbackFactory<PromotionClient> {

    @Override
    public PromotionClient create(Throwable cause) {
        return new PromotionClient() {
            @Override
            public Result<UserCouponDTO> checkUsable(Long userId, Long userCouponId) {
                log.error("PromotionClient.checkUsable 降级 userId={} userCouponId={}", userId, userCouponId, cause);
                throw new BusinessException(5003, "优惠服务暂不可用，请稍后重试");
            }

            @Override
            public Result<CouponTemplateDTO> getTemplate(Long id) {
                log.error("PromotionClient.getTemplate 降级 id={}", id, cause);
                throw new BusinessException(5003, "优惠服务暂不可用，请稍后重试");
            }

            @Override
            public Result<Void> markUsed(MarkUsedDTO dto) {
                log.error("PromotionClient.markUsed 降级 dto={}", dto, cause);
                throw new BusinessException(5003, "优惠服务暂不可用，下单失败");
            }

            @Override
            public Result<Void> refundByOrderNo(String orderNo) {
                log.error("PromotionClient.refundByOrderNo 降级 orderNo={}", orderNo, cause);
                return Result.success();
            }
        };
    }
}
