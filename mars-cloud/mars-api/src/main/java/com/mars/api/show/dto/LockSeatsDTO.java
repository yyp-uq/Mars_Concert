package com.mars.api.show.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 选座下单锁座请求：锁 owner 用 orderNo 关联订单 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LockSeatsDTO {
    private Long userId;
    private Long eventId;
    private List<Long> seatIds;
    private String orderNo;
}
