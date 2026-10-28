package com.mars.concert.search.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 歌曲，对应 ct_song 表（ES 搜索数据源） */
@Data
@TableName("ct_song")
public class Song {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String nameZh;
    private String nameEn;
    private String album;
    private String artistName;
    /** 1上架 0下架 */
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted;
}
