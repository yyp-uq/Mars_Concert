package com.mars.show.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("show_venue")
public class Venue {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String city;
    private String address;
    private Integer capacity;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
