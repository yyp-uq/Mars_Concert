package com.mars.show.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum EventStatus {
    DRAFT(0, "草稿"), PRESALE(1, "预售"), ONSALE(2, "售票中"),
    SOLD_OUT(3, "售罄"), FINISHED(4, "已结束"), CANCELLED(5, "取消");
    private final int code;
    private final String desc;
}
