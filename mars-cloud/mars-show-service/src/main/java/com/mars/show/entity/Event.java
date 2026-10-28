package com.mars.show.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("show_event")
public class Event {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private Long venueId;
    private String sessionType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime saleStart;
    private LocalDateTime saleEnd;
    private String guest;
    private Integer status;
    private Integer limitPerOrder;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
    @Version
    private Integer version;
}
