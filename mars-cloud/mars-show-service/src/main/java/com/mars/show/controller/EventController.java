package com.mars.show.controller;

import com.mars.common.result.PageResult;
import com.mars.common.result.Result;
import com.mars.show.model.dto.EventSaveDTO;
import com.mars.show.model.dto.EventTierBindDTO;
import com.mars.show.model.query.EventQuery;
import com.mars.show.model.vo.EventVO;
import com.mars.show.model.vo.MissionHomeVO;
import com.mars.show.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "演出-场次")
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "首页聚合(公开)")
    @GetMapping("/home")
    public Result<MissionHomeVO> home() {
        return Result.success(eventService.home());
    }

    @Operation(summary = "场次分页列表(C端公开)")
    @GetMapping
    public Result<PageResult<EventVO>> page(EventQuery q) {
        return Result.success(eventService.pageOnSale(q));
    }

    @Operation(summary = "场次详情(含票档余票,公开)")
    @GetMapping("/{id}")
    public Result<EventVO> detail(@PathVariable Long id) {
        return Result.success(eventService.detail(id));
    }

    @Operation(summary = "后台创建场次")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody EventSaveDTO dto) {
        return Result.success(eventService.createEvent(dto));
    }

    @Operation(summary = "后台更新场次")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody EventSaveDTO dto) {
        eventService.updateEvent(id, dto);
        return Result.success();
    }

    @Operation(summary = "后台配置场次票档库存")
    @PutMapping("/{id}/tiers")
    public Result<Void> bindTiers(@PathVariable Long id, @RequestBody List<EventTierBindDTO> binds) {
        eventService.bindTiers(id, binds);
        return Result.success();
    }
}
