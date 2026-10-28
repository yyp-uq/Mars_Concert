package com.mars.concert.show.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EventSaveDTO {
    @NotBlank(message = "场次标题不能为空")
    private String title;
    @NotNull(message = "场馆不能为空")
    private Long venueId;
    private String sessionType;
    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStart;
    private LocalDateTime saleEnd;
    private String guest;
    private Integer status;
    private Integer limitPerOrder;
}
