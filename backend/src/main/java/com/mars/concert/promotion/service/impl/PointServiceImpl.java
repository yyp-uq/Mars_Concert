package com.mars.concert.promotion.service.impl;

import com.mars.concert.common.constant.RedisKeys;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.utils.RedisService;
import com.mars.concert.promotion.entity.PointAccount;
import com.mars.concert.promotion.entity.PointLog;
import com.mars.concert.promotion.enums.PromotionErrorCode;
import com.mars.concert.promotion.mapper.PointAccountMapper;
import com.mars.concert.promotion.mapper.PointLogMapper;
import com.mars.concert.promotion.service.PointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointServiceImpl implements PointService {

    /** 签到奖励分值（学习期规则固定） */
    private static final int SIGN_POINTS = 10;

    private final RedisService redis;
    private final PointAccountMapper accountMapper;
    private final PointLogMapper pointLogMapper;

    @Override
    public void sign(Long userId) {
        String today = LocalDate.now().toString();
        String key = String.format(RedisKeys.SIGN_DAY, userId, today);
        Boolean first = redis.setIfAbsent(key, "1", 24 * 3600L);
        if (Boolean.FALSE.equals(first)) {
            throw new BusinessException(PromotionErrorCode.SIGN_REPEAT);
        }
        earn(userId, SIGN_POINTS, "SIGN", today);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void earn(Long userId, int value, String type, String bizNo) {
        PointAccount acc = accountMapper.selectById(userId);
        if (acc == null) {
            acc = new PointAccount();
            acc.setUserId(userId);
            acc.setBalance(0);
            accountMapper.insert(acc);
        }
        acc.setBalance(acc.getBalance() + value);
        // @Version 乐观锁：并发更新 affected=0，抛异常让调用方重试/进死信
        int rows = accountMapper.updateById(acc);
        if (rows == 0) {
            throw new BusinessException(PromotionErrorCode.POINT_CONFLICT);
        }
        PointLog pl = new PointLog();
        pl.setUserId(userId);
        pl.setChangeValue(value);
        pl.setType(type);
        pl.setBizNo(bizNo);
        pl.setBalanceAfter(acc.getBalance());
        pointLogMapper.insert(pl);
    }

    @Override
    public Integer balance(Long userId) {
        PointAccount acc = accountMapper.selectById(userId);
        return acc == null ? 0 : acc.getBalance();
    }
}
