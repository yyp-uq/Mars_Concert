package com.mars.concert.promotion.service;

public interface PointService {

    /** 每日签到：一人一天一次，+10 分 */
    void sign(Long userId);

    /**
     * 积分入账（乐观锁防并发超扣；pm_point_log uk_type_biz 唯一索引做消费幂等，
     * 重复投递时抛 DuplicateKeyException 由调用方按幂等成功处理）。
     */
    void earn(Long userId, int value, String type, String bizNo);

    /** 查询余额（无账户视为 0） */
    Integer balance(Long userId);
}
