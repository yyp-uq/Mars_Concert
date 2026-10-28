package com.mars.concert.promotion.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.promotion.entity.CouponTemplate;
import com.mars.concert.promotion.entity.UserCoupon;
import com.mars.concert.promotion.enums.PromotionErrorCode;
import com.mars.concert.promotion.mapper.CouponTemplateMapper;
import com.mars.concert.promotion.mapper.UserCouponMapper;
import com.mars.concert.promotion.model.vo.UserCouponVO;
import com.mars.concert.promotion.service.CouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final StringRedisTemplate redis;
    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receive(Long userId, Long tplId) {
        CouponTemplate t = templateMapper.selectById(tplId);
        if (t == null || t.getStatus() == null || t.getStatus() != 1) {
            throw new BusinessException(PromotionErrorCode.COUPON_NOT_FOUND);
        }

        String userKey = String.format(RedisKeys.COUPON_USER, tplId, userId);
        String stockKey = String.format(RedisKeys.COUPON_STOCK, tplId);

        // ① 一人一券：SETNX 原子占位（per_user_limit=1 场景）
        Boolean firstUser = redis.opsForValue().setIfAbsent(userKey, "1", 30, TimeUnit.DAYS);
        if (Boolean.FALSE.equals(firstUser)) {
            throw new BusinessException(PromotionErrorCode.COUPON_RECEIVED);
        }

        // ② 库存原子扣减。学习期无预热任务：首次领取用发行总量兜底初始化（setIfAbsent 并发安全）
        if (Boolean.FALSE.equals(redis.hasKey(stockKey))) {
            redis.opsForValue().setIfAbsent(stockKey, String.valueOf(t.getTotalCount()));
        }
        Long left = redis.opsForValue().decrement(stockKey);
        if (left != null && left < 0) {
            // 抢超了：回补库存并释放一人一券标记
            redis.opsForValue().increment(stockKey);
            redis.delete(userKey);
            throw new BusinessException(PromotionErrorCode.COUPON_SOLD_OUT);
        }

        // ③ DB 唯一索引 uk_tpl_user 兜底（并发穿透 Redis 的极小概率）
        try {
            UserCoupon uc = new UserCoupon();
            uc.setTemplateId(tplId);
            uc.setUserId(userId);
            uc.setStatus("UNUSED");
            uc.setValidStart(LocalDateTime.now());
            uc.setValidEnd(LocalDateTime.now().plusDays(t.getValidDays()));
            userCouponMapper.insert(uc);
        } catch (DuplicateKeyException dup) {
            throw new BusinessException(PromotionErrorCode.COUPON_RECEIVED);
        }
    }

    @Override
    public List<UserCouponVO> myCoupons(Long userId, String status) {
        var q = Wrappers.<UserCoupon>lambdaQuery().eq(UserCoupon::getUserId, userId);
        if (status != null && !status.isBlank()) q.eq(UserCoupon::getStatus, status);
        q.orderByDesc(UserCoupon::getId);
        List<UserCoupon> coupons = userCouponMapper.selectList(q);
        if (coupons.isEmpty()) return List.of();

        Map<Long, CouponTemplate> tplMap = templateMapper.selectBatchIds(
                        coupons.stream().map(UserCoupon::getTemplateId).distinct().toList())
                .stream().collect(Collectors.toMap(CouponTemplate::getId, Function.identity()));

        List<UserCouponVO> list = new ArrayList<>();
        for (UserCoupon uc : coupons) {
            CouponTemplate t = tplMap.get(uc.getTemplateId());
            UserCouponVO vo = new UserCouponVO();
            vo.setId(uc.getId());
            vo.setTemplateId(uc.getTemplateId());
            vo.setStatus(uc.getStatus());
            vo.setOrderNo(uc.getOrderNo());
            vo.setValidStart(uc.getValidStart());
            vo.setValidEnd(uc.getValidEnd());
            if (t != null) {
                vo.setName(t.getName());
                vo.setType(t.getType());
                vo.setFaceValue(t.getFaceValue());
                vo.setThreshold(t.getThreshold());
                vo.setScope(t.getScope());
                vo.setScopeId(t.getScopeId());
            }
            list.add(vo);
        }
        return list;
    }

    @Override
    public UserCoupon checkUsable(Long userId, Long userCouponId) {
        UserCoupon uc = userCouponMapper.selectById(userCouponId);
        LocalDateTime now = LocalDateTime.now();
        if (uc == null || !uc.getUserId().equals(userId)
                || !"UNUSED".equals(uc.getStatus())
                || now.isBefore(uc.getValidStart()) || now.isAfter(uc.getValidEnd())) {
            throw new BusinessException(PromotionErrorCode.COUPON_NOT_USABLE);
        }
        return uc;
    }

    @Override
    public CouponTemplate getTemplate(Long templateId) {
        CouponTemplate t = templateMapper.selectById(templateId);
        if (t == null) throw new BusinessException(PromotionErrorCode.COUPON_NOT_FOUND);
        return t;
    }

    @Override
    public void markUsed(Long userCouponId, Long userId, String orderNo) {
        int rows = userCouponMapper.useCoupon(userCouponId, userId, orderNo);
        if (rows == 0) {
            throw new BusinessException(PromotionErrorCode.COUPON_NOT_USABLE);
        }
    }

    @Override
    public void refundByOrderNo(String orderNo) {
        int rows = userCouponMapper.refundByOrderNo(orderNo);
        if (rows > 0) log.info("券已回退 orderNo={}", orderNo);
    }
}
