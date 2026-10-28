package com.mars.api.show;

import com.mars.api.show.dto.*;
import com.mars.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 演出/库存服务契约（第8卷 §9.1）。
 * /internal/** 是服务间内部接口，网关不对外暴露。
 * 调用方和提供方都依赖本模块：提供方写普通 Controller 实现相同路径。
 */
@FeignClient(name = "mars-show-service", path = "/internal/show",
        fallbackFactory = ShowClientFallback.class)
public interface ShowClient {

    /** 查场次基础信息（下单校验可售/限购；订单详情补标题） */
    @GetMapping("/events/{id}")
    Result<EventInfoDTO> getEvent(@PathVariable("id") Long id);

    /** 查场次票档真实价格（服务端算价，防止前端改价） */
    @GetMapping("/events/{eventId}/tiers/{tierId}")
    Result<TierInfoDTO> getTierInfo(@PathVariable("eventId") Long eventId,
                                    @PathVariable("tierId") Long tierId);

    /** 站票扣库存：DB 条件更新防超卖，data=false 表示余票不足 */
    @PostMapping("/inventory/deduct")
    Result<Boolean> deductStock(@RequestBody DeductStockDTO dto);

    /** 站票回补库存（取消/超时关单） */
    @PostMapping("/inventory/add")
    Result<Void> addStock(@RequestBody AddStockDTO dto);

    /** 选座下单：Lua 原子锁座（Redis 余票-1），失败抛业务异常 */
    @PostMapping("/seats/lock")
    Result<Void> lockSeats(@RequestBody LockSeatsDTO dto);

    /** 批量查场次座位实例（锁座后由座位推导票档明细） */
    @PostMapping("/seats/list")
    Result<List<SeatInfoDTO>> listEventSeats(@RequestBody SeatListDTO dto);

    /** 关单/超时按单号释放预占座位（幂等） */
    @PostMapping("/seats/release-by-order")
    Result<Void> releaseByOrderNo(@RequestParam("orderNo") String orderNo);
}
