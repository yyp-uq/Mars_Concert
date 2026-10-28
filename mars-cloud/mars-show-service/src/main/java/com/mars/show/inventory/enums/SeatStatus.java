package com.mars.show.inventory.enums;

/** 座位状态枚举：Redis Hash 中存 code（F/L/S），DB show_event_seat.status 存 name */
public enum SeatStatus {
    FREE("F"), LOCKED("L"), SOLD("S");
    private final String code;
    SeatStatus(String c) { this.code = c; }
    public String code() { return code; }
}
