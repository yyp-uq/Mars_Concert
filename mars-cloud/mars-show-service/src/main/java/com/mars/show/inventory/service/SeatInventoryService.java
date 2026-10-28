package com.mars.show.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.common.constant.RedisKeys;
import com.mars.common.enums.CommonErrorCode;
import com.mars.common.exception.BusinessException;
import com.mars.common.utils.OrderNoGenerator;
import com.mars.show.entity.Event;
import com.mars.show.entity.EventTier;
import com.mars.show.enums.ShowErrorCode;
import com.mars.show.inventory.entity.EventSeat;
import com.mars.show.inventory.entity.Seat;
import com.mars.show.inventory.entity.VenueArea;
import com.mars.show.inventory.enums.SeatStatus;
import com.mars.show.inventory.lua.SeatLockScript;
import com.mars.show.inventory.mapper.EventSeatMapper;
import com.mars.show.inventory.mapper.SeatMapper;
import com.mars.show.inventory.mapper.VenueAreaMapper;
import com.mars.show.inventory.model.dto.PaidReleaseItemDTO;
import com.mars.show.inventory.model.vo.SeatMapVO;
import com.mars.show.mapper.EventMapper;
import com.mars.show.mapper.EventTierMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 选座 Service：Lua 原子锁座 / 释放 / 座位图查询。
 * Redis 是实时真相，MySQL 是最终账本：锁座成功同步写 DB lock_owner/lock_expire 供兜底对账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatInventoryService {

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final SeatLockScript seatLockScript;
    private final EventSeatMapper eventSeatMapper;
    private final SeatMapper seatMapper;
    private final VenueAreaMapper venueAreaMapper;
    private final EventMapper eventMapper;
    private final EventTierMapper eventTierMapper;

    /** 锁座固定 5 分钟 TTL（到期必须释放给别人，故显式租期、不用看门狗） */
    private static final int LOCK_TTL = 300;

    /** 锁多个座位（自生成预占单号并返回，供下单绑定） */
    public String lockSeats(Long userId, Long eventId, List<Long> seatIds) {
        String orderNo = OrderNoGenerator.next();
        lockSeats(userId, eventId, seatIds, orderNo);
        return orderNo;
    }

    /**
     * 用 Redisson 分布式锁串住同一场次的并发，再逐座执行原子 Lua，
     * 保证「一批座位要么全锁成功，要么全部不锁」。
     */
    public List<Long> lockSeats(Long userId, Long eventId, List<Long> seatIds, String orderNo) {
        List<Long> locked = new ArrayList<>();
        RLock guard = redisson.getLock(String.format(RedisKeys.EVENT_GUARD_LOCK, eventId));
        boolean got = false;
        try {
            got = guard.tryLock(2, LOCK_TTL, TimeUnit.SECONDS);
            if (!got) {
                throw new BusinessException(ShowErrorCode.EVENT_BUSY.getCode(), "当前选座人数过多，请重试");
            }

            // 校验座位属于该场次并取出票档
            List<EventSeat> rows = eventSeatMapper.selectList(Wrappers.<EventSeat>lambdaQuery()
                    .eq(EventSeat::getEventId, eventId).in(EventSeat::getSeatId, seatIds));
            Map<Long, EventSeat> rowMap = rows.stream()
                    .collect(Collectors.toMap(EventSeat::getSeatId, s -> s));
            if (rowMap.size() != seatIds.size()) {
                throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "存在无效座位");
            }

            String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, eventId);
            String owner = userId + ":" + orderNo;
            for (Long seatId : seatIds) {
                String stockKey = String.format(RedisKeys.EVENT_TIER_STOCK, eventId, rowMap.get(seatId).getTierId());
                String lockKey = String.format(RedisKeys.SEAT_LOCK, eventId, seatId);
                Long r = seatLockScript.lock(redis, List.of(hashKey, stockKey, lockKey),
                        String.valueOf(seatId), owner, LOCK_TTL);
                if (r == null || r != 1L) {
                    // 任一失败 → 回滚已锁
                    rollback(eventId, userId, orderNo, locked);
                    throw new BusinessException(ShowErrorCode.SEAT_TAKEN.getCode(),
                            r != null && r == -1 ? "座位已被他人选中" : "座位不可选");
                }
                locked.add(seatId);
            }

            // 记录订单预占座位集合，供关单/超时释放
            redis.opsForValue().set(String.format(RedisKeys.SEAT_LOCK_ORDER, orderNo),
                    userId + "|" + eventId + "|" + locked.stream().map(String::valueOf).collect(Collectors.joining(",")),
                    LOCK_TTL, TimeUnit.SECONDS);

            // DB 兜底账本：LOCKED + lock_owner + lock_expire（对账任务据此补偿）
            syncDbLocked(eventId, locked, owner);
            return locked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        } finally {
            if (got && guard.isHeldByCurrentThread()) guard.unlock();
        }
    }

    /** 用户主动释放（只有锁持有者能释放，Lua 内校验 owner） */
    public void releaseSeats(Long userId, Long eventId, List<Long> seatIds, String orderNo) {
        String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, eventId);
        String owner = userId + ":" + orderNo;
        List<EventSeat> rows = eventSeatMapper.selectList(Wrappers.<EventSeat>lambdaQuery()
                .eq(EventSeat::getEventId, eventId).in(EventSeat::getSeatId, seatIds));
        Map<Long, Long> tierMap = rows.stream()
                .collect(Collectors.toMap(EventSeat::getSeatId, EventSeat::getTierId));
        for (Long seatId : seatIds) {
            Long tierId = tierMap.get(seatId);
            if (tierId == null) continue;
            String stockKey = String.format(RedisKeys.EVENT_TIER_STOCK, eventId, tierId);
            String lockKey = String.format(RedisKeys.SEAT_LOCK, eventId, seatId);
            seatLockScript.release(redis, List.of(hashKey, stockKey, lockKey), String.valueOf(seatId), owner);
        }
        // DB 账本对齐（仅释放本人锁）
        eventSeatMapper.update(null, Wrappers.<EventSeat>lambdaUpdate()
                .eq(EventSeat::getEventId, eventId)
                .in(EventSeat::getSeatId, seatIds)
                .eq(EventSeat::getLockOwner, owner)
                .eq(EventSeat::getStatus, SeatStatus.LOCKED.name())
                .set(EventSeat::getStatus, SeatStatus.FREE.name())
                .set(EventSeat::getLockOwner, null)
                .set(EventSeat::getLockExpire, null));
        redis.delete(String.format(RedisKeys.SEAT_LOCK_ORDER, orderNo));
    }

    /** 关单/超时释放（系统身份，按 orderNo 找到座位） */
    public void releaseByOrderNo(String orderNo) {
        String val = redis.opsForValue().get(String.format(RedisKeys.SEAT_LOCK_ORDER, orderNo));
        if (val == null) return;
        String[] parts = val.split("\\|");
        Long userId = Long.valueOf(parts[0]);
        Long eventId = Long.valueOf(parts[1]);
        if (parts.length < 3 || parts[2].isBlank()) return;
        List<Long> seatIds = Arrays.stream(parts[2].split(",")).map(Long::valueOf).toList();
        releaseSeats(userId, eventId, seatIds, orderNo);
        log.info("按单号 {} 释放座位 {} 个", orderNo, seatIds.size());
    }

    /** 查询场次座位（下单 Service 用） */
    public List<EventSeat> listEventSeats(Long eventId, List<Long> seatIds) {
        return eventSeatMapper.selectList(Wrappers.<EventSeat>lambdaQuery()
                .eq(EventSeat::getEventId, eventId).in(EventSeat::getSeatId, seatIds));
    }

    /**
     * 退款「已售库存回补」：坐票 Redis/DB 座位置 FREE，站票回补 EventTier 库存。
     * 支付成功的订单其锁早已过期，故这里直接强制置 FREE（不再走 owner 校验的释放 Lua）。
     * 第8卷：订单明细由调用方组装传入，show-service 不再跨域直查订单表。
     */
    public void releasePaid(Long eventId, List<PaidReleaseItemDTO> items) {
        for (PaidReleaseItemDTO it : items) {
            if (it.getSeatId() != null) {
                String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, eventId);
                String stockKey = String.format(RedisKeys.EVENT_TIER_STOCK, eventId, it.getTierId());
                String lockKey = String.format(RedisKeys.SEAT_LOCK, eventId, it.getSeatId());
                redis.opsForHash().put(hashKey, String.valueOf(it.getSeatId()), SeatStatus.FREE.code());
                redis.delete(lockKey);
                redis.opsForValue().increment(stockKey);
                eventSeatMapper.update(null, Wrappers.<EventSeat>lambdaUpdate()
                        .eq(EventSeat::getEventId, eventId)
                        .eq(EventSeat::getSeatId, it.getSeatId())
                        .set(EventSeat::getStatus, SeatStatus.FREE.name())
                        .set(EventSeat::getLockOwner, null)
                        .set(EventSeat::getLockExpire, null));
            } else {
                eventTierMapper.addStock(eventId, it.getTierId(), it.getQuantity());
            }
        }
        log.info("退款回补库存 eventId={} 明细数={}", eventId, items.size());
    }

    /** 座位图：DB 静态坐标 + Redis Hash 动态状态合并；本人锁定标 mine=true */
    public SeatMapVO getSeatMap(Long eventId, Long userId) {
        Event event = eventMapper.selectById(eventId);
        if (event == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);

        // 动态状态：优先 Redis Hash；未预热时回源 DB
        String hashKey = String.format(RedisKeys.EVENT_SEAT_HASH, eventId);
        Map<Object, Object> redisStatus = redis.opsForHash().entries(hashKey);

        List<EventSeat> eventSeats = eventSeatMapper.selectList(
                Wrappers.<EventSeat>lambdaQuery().eq(EventSeat::getEventId, eventId));
        Map<Long, EventSeat> esMap = eventSeats.stream()
                .collect(Collectors.toMap(EventSeat::getSeatId, s -> s));

        List<VenueArea> areas = venueAreaMapper.selectList(
                Wrappers.<VenueArea>lambdaQuery().eq(VenueArea::getVenueId, event.getVenueId()));
        List<Seat> seats = areas.isEmpty() ? List.of() : seatMapper.selectList(
                Wrappers.<Seat>lambdaQuery().in(Seat::getAreaId, areas.stream().map(VenueArea::getId).toList()));
        Map<Long, List<Seat>> seatsByArea = seats.stream().collect(Collectors.groupingBy(Seat::getAreaId));

        // 站票余票：各票档 Redis 余票求和（未预热回源 DB）
        Integer standRemain = null;
        List<EventTier> tiers = eventTierMapper.selectList(
                Wrappers.<EventTier>lambdaQuery().eq(EventTier::getEventId, eventId));
        if (!tiers.isEmpty()) {
            int sum = 0;
            for (EventTier t : tiers) {
                String v = redis.opsForValue().get(String.format(RedisKeys.EVENT_TIER_STOCK, eventId, t.getTierId()));
                sum += v != null ? Integer.parseInt(v)
                        : t.getTotalStock() - t.getSoldStock() - t.getLockedStock();
            }
            standRemain = sum;
        }

        SeatMapVO vo = new SeatMapVO();
        vo.setEventId(eventId);
        List<SeatMapVO.AreaVO> areaVOs = new ArrayList<>();
        for (VenueArea area : areas) {
            SeatMapVO.AreaVO av = new SeatMapVO.AreaVO();
            av.setAreaId(area.getId());
            av.setCode(area.getCode());
            av.setName(area.getName());
            av.setType(area.getType());
            if ("STAND".equals(area.getType())) {
                av.setStandRemain(standRemain);
                av.setSeats(List.of());
            } else {
                List<SeatMapVO.SeatVO> svs = new ArrayList<>();
                for (Seat s : seatsByArea.getOrDefault(area.getId(), List.of())) {
                    EventSeat es = esMap.get(s.getId());
                    if (es == null) continue;   // 该座位未生成本场次实例
                    SeatMapVO.SeatVO sv = new SeatMapVO.SeatVO();
                    sv.setSeatId(s.getId());
                    sv.setSeatNo(s.getSeatNo());
                    sv.setX(s.getX());
                    sv.setY(s.getY());
                    sv.setTierId(es.getTierId());
                    Object st = redisStatus.get(String.valueOf(s.getId()));
                    String code = st != null ? st.toString()
                            : SeatStatus.valueOf(es.getStatus()).code();
                    sv.setStatus(code);
                    // 本人锁定标记
                    boolean mine = false;
                    if (userId != null && SeatStatus.LOCKED.code().equals(code)) {
                        String owner = redis.opsForValue().get(
                                String.format(RedisKeys.SEAT_LOCK, eventId, s.getId()));
                        mine = owner != null && owner.startsWith(userId + ":");
                    }
                    sv.setMine(mine);
                    svs.add(sv);
                }
                av.setSeats(svs);
            }
            areaVOs.add(av);
        }
        vo.setAreas(areaVOs);
        return vo;
    }

    private void rollback(Long eventId, Long userId, String orderNo, List<Long> locked) {
        if (!locked.isEmpty()) {
            releaseSeats(userId, eventId, locked, orderNo);
        }
    }

    private void syncDbLocked(Long eventId, List<Long> seatIds, String owner) {
        eventSeatMapper.update(null, Wrappers.<EventSeat>lambdaUpdate()
                .eq(EventSeat::getEventId, eventId)
                .in(EventSeat::getSeatId, seatIds)
                .set(EventSeat::getStatus, SeatStatus.LOCKED.name())
                .set(EventSeat::getLockOwner, owner)
                .set(EventSeat::getLockExpire, LocalDateTime.now().plusSeconds(LOCK_TTL)));
    }
}
