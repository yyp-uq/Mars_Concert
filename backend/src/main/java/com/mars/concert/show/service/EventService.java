package com.mars.concert.show.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mars.concert.common.result.PageResult;
import com.mars.concert.show.entity.Event;
import com.mars.concert.show.model.dto.EventSaveDTO;
import com.mars.concert.show.model.dto.EventTierBindDTO;
import com.mars.concert.show.model.query.EventQuery;
import com.mars.concert.show.model.vo.EventVO;
import com.mars.concert.show.model.vo.MissionHomeVO;

import java.util.List;

public interface EventService extends IService<Event> {
    PageResult<EventVO> pageOnSale(EventQuery q);
    EventVO detail(Long id);
    MissionHomeVO home();
    Long createEvent(EventSaveDTO dto);
    void updateEvent(Long id, EventSaveDTO dto);
    void bindTiers(Long eventId, List<EventTierBindDTO> binds);
}
