package com.mars.api.show.dto;

import lombok.Data;

/** 场次座位实例（下单时由座位推导票档明细用） */
@Data
public class SeatInfoDTO {
    private Long seatId;
    private Long tierId;
}
