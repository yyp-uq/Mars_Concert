package com.mars.concert.show.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 静态座位模板（show_seat）：一个场馆的座位坐标，可被多个场次复用 */
@Data
@TableName("show_seat")
public class Seat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long venueId;
    private Long areaId;
    /** 如 A-5-12 */
    private String seatNo;
    private Integer x;
    private Integer y;
}
