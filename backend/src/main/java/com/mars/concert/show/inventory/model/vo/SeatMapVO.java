package com.mars.concert.show.inventory.model.vo;

import lombok.Data;

import java.util.List;

/** 座位图：静态坐标 + 动态状态 */
@Data
public class SeatMapVO {
    private Long eventId;
    private List<AreaVO> areas;

    @Data
    public static class AreaVO {
        private Long areaId;
        private String code;
        private String name;
        private String type;
        private List<SeatVO> seats;
        /** 站票余票 */
        private Integer standRemain;
    }

    @Data
    public static class SeatVO {
        private Long seatId;
        private String seatNo;
        private Integer x;
        private Integer y;
        /** F/L/S */
        private String status;
        private Long tierId;
        /** 当前用户锁的 */
        private Boolean mine;
    }
}
