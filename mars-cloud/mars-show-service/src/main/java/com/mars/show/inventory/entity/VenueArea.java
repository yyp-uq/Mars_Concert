package com.mars.show.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 场馆分区（show_venue_area） */
@Data
@TableName("show_venue_area")
public class VenueArea {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long venueId;
    private String code;
    private String name;
    /** SEATED坐票 STAND站票 */
    private String type;
    private Integer capacity;
}
