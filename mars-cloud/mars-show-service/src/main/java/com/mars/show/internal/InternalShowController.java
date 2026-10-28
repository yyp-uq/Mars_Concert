package com.mars.show.internal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.api.show.dto.*;
import com.mars.common.result.Result;
import com.mars.show.entity.Event;
import com.mars.show.entity.EventTier;
import com.mars.show.entity.TicketTier;
import com.mars.show.inventory.entity.EventSeat;
import com.mars.show.inventory.service.SeatInventoryService;
import com.mars.show.mapper.EventMapper;
import com.mars.show.mapper.EventTierMapper;
import com.mars.show.mapper.TicketTierMapper;
import com.mars.show.model.vo.TierStockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 服务间内部接口（第8卷 §9.2）：实现 mars-api 中 ShowClient 契约相同的路径，
 * 复用本服务自己的 Mapper/Service。/internal/** 不在网关路由中，外部不可达。
 */
@RestController
@RequestMapping("/internal/show")
@RequiredArgsConstructor
public class InternalShowController {

    private final EventMapper eventMapper;
    private final EventTierMapper eventTierMapper;
    private final TicketTierMapper ticketTierMapper;
    private final SeatInventoryService seatInventoryService;

    /** 查场次基础信息（下单校验/订单详情补标题用） */
    @GetMapping("/events/{id}")
    public Result<EventInfoDTO> getEvent(@PathVariable("id") Long id) {
        Event e = eventMapper.selectById(id);
        if (e == null) return Result.success(null);
        EventInfoDTO dto = new EventInfoDTO();
        dto.setId(e.getId());
        dto.setTitle(e.getTitle());
        dto.setStatus(e.getStatus());
        dto.setSaleStart(e.getSaleStart());
        dto.setSaleEnd(e.getSaleEnd());
        dto.setLimitPerOrder(e.getLimitPerOrder());
        return Result.success(dto);
    }

    /** 查场次票档真实价格（status=1 在售才算，与单体下单口径一致） */
    @GetMapping("/events/{eventId}/tiers/{tierId}")
    public Result<TierInfoDTO> getTierInfo(@PathVariable("eventId") Long eventId,
                                           @PathVariable("tierId") Long tierId) {
        EventTier et = eventTierMapper.selectOne(Wrappers.<EventTier>lambdaQuery()
                .eq(EventTier::getEventId, eventId)
                .eq(EventTier::getTierId, tierId)
                .eq(EventTier::getStatus, 1));
        if (et == null) return Result.success(null);
        TicketTier tier = ticketTierMapper.selectById(tierId);
        TierInfoDTO dto = new TierInfoDTO();
        dto.setId(et.getId());
        dto.setEventId(et.getEventId());
        dto.setTierId(et.getTierId());
        dto.setTierName(tier == null ? "票档" : tier.getName());
        dto.setPrice(et.getPrice());
        return Result.success(dto);
    }

    /** 查场次各票档余票（第9卷：AI 客服实时查询，余票 = 总库存 - 锁定 - 已售） */
    @GetMapping("/events/{eventId}/tiers/stock")
    public Result<List<TierStockVO>> listTierStock(@PathVariable("eventId") Long eventId) {
        List<EventTier> rows = eventTierMapper.selectList(Wrappers.<EventTier>lambdaQuery()
                .eq(EventTier::getEventId, eventId)
                .eq(EventTier::getStatus, 1));
        if (rows.isEmpty()) return Result.success(List.of());

        Map<Long, TicketTier> tierMap = ticketTierMapper.selectBatchIds(
                        rows.stream().map(EventTier::getTierId).distinct().toList())
                .stream().collect(Collectors.toMap(TicketTier::getId, t -> t));

        List<TierStockVO> list = rows.stream().map(et -> {
            TierStockVO vo = new TierStockVO();
            TicketTier tier = tierMap.get(et.getTierId());
            vo.setTierId(et.getTierId());
            vo.setName(tier == null ? "票档" : tier.getName());
            vo.setPrice(et.getPrice());
            int total = et.getTotalStock() == null ? 0 : et.getTotalStock();
            int locked = et.getLockedStock() == null ? 0 : et.getLockedStock();
            int sold = et.getSoldStock() == null ? 0 : et.getSoldStock();
            vo.setRemainStock(Math.max(0, total - locked - sold));
            return vo;
        }).toList();
        return Result.success(list);
    }

    /** 站票扣库存：条件更新防超卖，affected=0 即余票不足 */
    @PostMapping("/inventory/deduct")
    public Result<Boolean> deductStock(@RequestBody DeductStockDTO dto) {
        int rows = eventTierMapper.deductStock(dto.getId(), dto.getQuantity());
        return Result.success(rows > 0);
    }

    /** 站票回补库存（取消/超时关单/退款） */
    @PostMapping("/inventory/add")
    public Result<Void> addStock(@RequestBody AddStockDTO dto) {
        eventTierMapper.addStock(dto.getEventId(), dto.getTierId(), dto.getQuantity());
        return Result.success();
    }

    /** 选座下单锁座：本地事务内 Lua 原子锁 + DB 账本对齐 */
    @PostMapping("/seats/lock")
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> lockSeats(@RequestBody LockSeatsDTO dto) {
        seatInventoryService.lockSeats(dto.getUserId(), dto.getEventId(), dto.getSeatIds(), dto.getOrderNo());
        return Result.success();
    }

    /** 批量查场次座位实例（锁座后由座位推导票档明细） */
    @PostMapping("/seats/list")
    public Result<List<SeatInfoDTO>> listEventSeats(@RequestBody SeatListDTO dto) {
        List<EventSeat> rows = seatInventoryService.listEventSeats(dto.getEventId(), dto.getSeatIds());
        List<SeatInfoDTO> list = rows.stream().map(es -> {
            SeatInfoDTO s = new SeatInfoDTO();
            s.setSeatId(es.getSeatId());
            s.setTierId(es.getTierId());
            return s;
        }).toList();
        return Result.success(list);
    }

    /** 关单/超时按单号释放预占座位（幂等） */
    @PostMapping("/seats/release-by-order")
    public Result<Void> releaseByOrderNo(@RequestParam("orderNo") String orderNo) {
        seatInventoryService.releaseByOrderNo(orderNo);
        return Result.success();
    }
}
