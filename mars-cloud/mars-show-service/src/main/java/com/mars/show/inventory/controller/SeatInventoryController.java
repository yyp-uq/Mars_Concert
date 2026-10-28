package com.mars.show.inventory.controller;

import com.mars.common.result.Result;
import com.mars.common.web.CurrentUser;
import com.mars.show.inventory.model.dto.LockSeatDTO;
import com.mars.show.inventory.model.dto.ReleaseSeatDTO;
import com.mars.show.inventory.model.vo.SeatMapVO;
import com.mars.show.inventory.service.SeatInventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "演出-选座库存")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SeatInventoryController {

    private final SeatInventoryService seatInventoryService;

    @Operation(summary = "座位图查询(公开,登录后标mine)")
    @GetMapping("/events/{id}/seats")
    public Result<SeatMapVO> seatMap(@PathVariable Long id,
                                     @CurrentUser(required = false) Long userId) {
        return Result.success(seatInventoryService.getSeatMap(id, userId));
    }

    @Operation(summary = "锁定座位(返回预占单号,5分钟内下单)")
    @PostMapping("/seats/lock")
    public Result<String> lock(@CurrentUser Long userId,
                               @Valid @RequestBody LockSeatDTO dto) {
        return Result.success(seatInventoryService.lockSeats(userId, dto.getEventId(), dto.getSeatIds()));
    }

    @Operation(summary = "主动释放座位")
    @PostMapping("/seats/release")
    public Result<Void> release(@CurrentUser Long userId,
                                @Valid @RequestBody ReleaseSeatDTO dto) {
        seatInventoryService.releaseSeats(userId, dto.getEventId(), dto.getSeatIds(), dto.getOrderNo());
        return Result.success();
    }
}
