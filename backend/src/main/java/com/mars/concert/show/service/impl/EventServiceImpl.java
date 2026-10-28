package com.mars.concert.show.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.show.entity.Event;
import com.mars.concert.show.entity.EventTier;
import com.mars.concert.show.entity.TicketTier;
import com.mars.concert.show.entity.Venue;
import com.mars.concert.show.enums.EventStatus;
import com.mars.concert.show.mapper.EventMapper;
import com.mars.concert.show.mapper.EventTierMapper;
import com.mars.concert.show.mapper.TicketTierMapper;
import com.mars.concert.show.mapper.VenueMapper;
import com.mars.concert.show.model.dto.EventSaveDTO;
import com.mars.concert.show.model.dto.EventTierBindDTO;
import com.mars.concert.show.model.query.EventQuery;
import com.mars.concert.show.model.vo.EventVO;
import com.mars.concert.show.model.vo.MissionHomeVO;
import com.mars.concert.show.model.vo.TierStockVO;
import com.mars.concert.show.service.EventService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EventServiceImpl extends ServiceImpl<EventMapper, Event> implements EventService {

    private final VenueMapper venueMapper;
    private final EventTierMapper eventTierMapper;
    private final TicketTierMapper ticketTierMapper;

    @Override
    public PageResult<EventVO> pageOnSale(EventQuery q) {
        Page<Event> page = new Page<>(q.getPageNum(), q.getPageSize());
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        // C 端只看待售中（ONSALE=2）或预售（PRESALE=1），且售卖窗口内
        wrapper.in(Event::getStatus, EventStatus.PRESALE.getCode(), EventStatus.ONSALE.getCode());
        wrapper.le(Event::getSaleStart, LocalDateTime.now());
        wrapper.and(w -> w.isNull(Event::getSaleEnd).or().ge(Event::getSaleEnd, LocalDateTime.now()));
        if (q.getVenueId() != null) wrapper.eq(Event::getVenueId, q.getVenueId());
        wrapper.orderByAsc(Event::getStartTime);
        Page<Event> result = this.page(page, wrapper);
        List<EventVO> voList = result.getRecords().stream().map(this::toSimpleVO).toList();
        return PageResult.of(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public EventVO detail(Long id) {
        Event e = this.getById(id);
        if (e == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        return toFullVO(e);
    }

    @Override
    public MissionHomeVO home() {
        // 1. 查最近一场在售/预售且开始时间未过的场次
        LambdaQueryWrapper<Event> q = Wrappers.<Event>lambdaQuery()
                .in(Event::getStatus, EventStatus.PRESALE.getCode(), EventStatus.ONSALE.getCode())
                .orderByAsc(Event::getStartTime)
                .last("LIMIT 7");
        List<Event> events = this.list(q);

        MissionHomeVO home = new MissionHomeVO();
        if (events.isEmpty()) return home;

        Event first = events.get(0);
        home.setNextEventTime(first.getStartTime());
        home.setGuest(first.getGuest());

        // 2. 场次简要列表
        Venue venue = venueMapper.selectById(first.getVenueId());
        home.setVenue(toVenueBrief(venue));
        List<MissionHomeVO.EventBrief> briefs = events.stream().map(ev -> {
            MissionHomeVO.EventBrief b = new MissionHomeVO.EventBrief();
            b.setId(ev.getId());
            b.setTitle(ev.getTitle());
            b.setStartTime(ev.getStartTime());
            b.setGuest(ev.getGuest());
            if (venue != null) b.setCity(venue.getCity());
            return b;
        }).toList();
        home.setEvents(briefs);

        // 3. 票档价格区间（全局 TicketTier 的 min-max）
        List<TicketTier> tiers = ticketTierMapper.selectList(Wrappers.<TicketTier>lambdaQuery());
        if (!tiers.isEmpty()) {
            BigDecimal min = tiers.stream().map(TicketTier::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal max = tiers.stream().map(TicketTier::getPrice).max(BigDecimal::compareTo).orElse(min);
            home.setPriceRange("¥" + min.stripTrailingZeros().toPlainString() + " - ¥" + max.stripTrailingZeros().toPlainString());
        }
        return home;
    }

    @Override
    public Long createEvent(EventSaveDTO dto) {
        Event e = new Event();
        BeanUtils.copyProperties(dto, e);
        if (e.getSessionType() == null) e.setSessionType("EVENING");
        if (e.getStatus() == null) e.setStatus(EventStatus.DRAFT.getCode());
        if (e.getLimitPerOrder() == null) e.setLimitPerOrder(4);
        this.save(e);
        return e.getId();
    }

    @Override
    public void updateEvent(Long id, EventSaveDTO dto) {
        Event exist = this.getById(id);
        if (exist == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        Event e = new Event();
        BeanUtils.copyProperties(dto, e);
        e.setId(id);
        this.updateById(e);
    }

    @Override
    public void bindTiers(Long eventId, List<EventTierBindDTO> binds) {
        Event exist = this.getById(eventId);
        if (exist == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        // 先删老的
        eventTierMapper.delete(Wrappers.<EventTier>lambdaQuery().eq(EventTier::getEventId, eventId));
        for (EventTierBindDTO b : binds) {
            EventTier et = new EventTier();
            et.setEventId(eventId);
            et.setTierId(b.getTierId());
            et.setPrice(b.getPrice());
            et.setTotalStock(b.getTotalStock());
            et.setLockedStock(0);
            et.setSoldStock(0);
            et.setStatus(1);
            eventTierMapper.insert(et);
        }
    }

    private EventVO toSimpleVO(Event e) {
        EventVO vo = new EventVO();
        BeanUtils.copyProperties(e, vo);
        Venue v = venueMapper.selectById(e.getVenueId());
        if (v != null) { vo.setVenueName(v.getName()); vo.setCity(v.getCity()); }
        return vo;
    }

    private EventVO toFullVO(Event e) {
        EventVO vo = toSimpleVO(e);
        List<EventTier> ets = eventTierMapper.selectList(
                Wrappers.<EventTier>lambdaQuery().eq(EventTier::getEventId, e.getId()).eq(EventTier::getStatus, 1));
        List<TierStockVO> tierVOs = new ArrayList<>();
        for (EventTier et : ets) {
            TicketTier t = ticketTierMapper.selectById(et.getTierId());
            TierStockVO tv = new TierStockVO();
            tv.setTierId(et.getTierId());
            tv.setName(t == null ? "票档" : t.getName());
            tv.setPrice(et.getPrice());
            tv.setRemainStock(et.getTotalStock() - et.getSoldStock() - et.getLockedStock());
            tierVOs.add(tv);
        }
        vo.setTiers(tierVOs);
        return vo;
    }

    private MissionHomeVO.VenueBrief toVenueBrief(Venue v) {
        if (v == null) return null;
        MissionHomeVO.VenueBrief vb = new MissionHomeVO.VenueBrief();
        vb.setName(v.getName());
        vb.setCity(v.getCity());
        vb.setAddress(v.getAddress());
        return vb;
    }
}
