package com.mars.api.show.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 批量查场次座位实例请求 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SeatListDTO {
    private Long eventId;
    private List<Long> seatIds;
}
