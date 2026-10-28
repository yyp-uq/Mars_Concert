package com.mars.concert.order.service;

import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.promotion.entity.CouponTemplate;
import com.mars.concert.promotion.entity.UserCoupon;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class PriceEngine {
    /** 服务费：每张票固定 10 元（学习期规则） */
    private static final BigDecimal SERVICE_FEE_PER_TICKET = new BigDecimal("10.00");

    public OrderPrice calculate(List<PriceLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        int ticketCount = 0;
        for (PriceLine l : lines) {
            if (l.getQuantity() <= 0) throw new BusinessException(CommonErrorCode.PARAM_INVALID);
            total = total.add(l.getPrice().multiply(BigDecimal.valueOf(l.getQuantity())));
            ticketCount += l.getQuantity();
        }
        BigDecimal service = SERVICE_FEE_PER_TICKET.multiply(BigDecimal.valueOf(ticketCount));
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal pay = total.add(service).subtract(discount);
        if (pay.signum() < 0) pay = BigDecimal.ZERO;
        return new OrderPrice(total, service, discount, pay);
    }

    /**
     * 第6卷：带券算价（手册 §5.2）。
     * 券的归属/状态/有效期由 CouponService.checkUsable 前置校验；
     * 这里校验适用范围与门槛，不满足则优惠为 0（试算接口需要真实反馈，不打断下单流程）。
     */
    public OrderPrice calculate(List<PriceLine> lines, Long eventId, UserCoupon uc, CouponTemplate tpl) {
        OrderPrice base = calculate(lines);
        BigDecimal discount = BigDecimal.ZERO;
        if (uc != null && tpl != null && "UNUSED".equals(uc.getStatus())
                && scopeMatch(lines, eventId, tpl)
                && base.getTotalAmount().compareTo(tpl.getThreshold()) >= 0) {
            discount = switch (tpl.getType()) {
                case "FULL_CUT", "CASH" -> tpl.getFaceValue();
                case "DISCOUNT" -> base.getTotalAmount().multiply(
                        BigDecimal.ONE.subtract(tpl.getFaceValue().divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP)));
                default -> BigDecimal.ZERO;
            };
            // 优惠不超过票价合计
            if (discount.compareTo(base.getTotalAmount()) > 0) discount = base.getTotalAmount();
        }
        BigDecimal pay = base.getTotalAmount().add(base.getServiceAmount()).subtract(discount);
        return new OrderPrice(base.getTotalAmount(), base.getServiceAmount(), discount, pay.max(BigDecimal.ZERO));
    }

    /** 适用范围：ALL 全场 / EVENT 指定场次 / TIER 指定票档 */
    private boolean scopeMatch(List<PriceLine> lines, Long eventId, CouponTemplate tpl) {
        return switch (tpl.getScope()) {
            case "ALL" -> true;
            case "EVENT" -> tpl.getScopeId() != null && tpl.getScopeId().equals(eventId);
            case "TIER" -> tpl.getScopeId() != null
                    && lines.stream().anyMatch(l -> l.getTierId().equals(tpl.getScopeId()));
            default -> false;
        };
    }
}
