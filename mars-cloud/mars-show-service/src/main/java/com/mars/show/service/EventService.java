package com.mars.show.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mars.common.result.PageResult;
import com.mars.show.entity.Event;
import com.mars.show.model.dto.EventSaveDTO;
import com.mars.show.model.dto.EventTierBindDTO;
import com.mars.show.model.query.EventQuery;
import com.mars.show.model.vo.EventVO;
import com.mars.show.model.vo.MissionHomeVO;

import java.util.List;

public interface EventService extends IService<Event> {
    PageResult<EventVO> pageOnSale(EventQuery q);
    EventVO detail(Long id);
    MissionHomeVO home();
    Long createEvent(EventSaveDTO dto);
    void updateEvent(Long id, EventSaveDTO dto);
    void bindTiers(Long eventId, List<EventTierBindDTO> binds);
}
