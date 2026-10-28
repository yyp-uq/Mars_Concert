package com.mars.concert.member.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 实名观演人（表 uc_viewer） */
@Data
@TableName("uc_viewer")
public class Viewer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String idCard;
    private String phone;
    private Integer isDefault;       // 1默认观演人 0否
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
