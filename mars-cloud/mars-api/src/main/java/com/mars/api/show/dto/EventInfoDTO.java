package com.mars.api.show.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 场次基础信息（服务间契约，字段是 show_event 的子集） */
@Data
public class EventInfoDTO {
    private Long id;
    private String title;
    /** 0草稿 1预售 2售票中 3售罄 4已结束 5取消 */
    private Integer status;
    private LocalDateTime saleStart;
    private LocalDateTime saleEnd;
    private Integer limitPerOrder;
}
