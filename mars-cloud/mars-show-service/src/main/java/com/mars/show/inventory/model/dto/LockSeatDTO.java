package com.mars.show.inventory.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 锁座请求 */
@Data
public class LockSeatDTO {
    @NotNull(message = "场次不能为空")
    private Long eventId;

    @NotEmpty(message = "座位不能为空")
    private List<Long> seatIds;
}
