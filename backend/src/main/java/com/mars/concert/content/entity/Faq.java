package com.mars.concert.content.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FAQ 实体，与表 ct_faq 一一对应。
 *
 * @TableName 指定表名；类名不叫 CtFaq 是因为包已经表明域，表前缀 ct_ 仅用于数据库分组。
 */
@Data
@TableName("ct_faq")
public class Faq {

    /** 主键。IdType.AUTO 对应数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类 */
    private String category;

    /** 英文问题（@TableField 可省略，因为已开启下划线转驼峰，这里写出来作演示） */
    @TableField("question_en")
    private String questionEn;

    private String questionZh;

    private String answer;

    private Integer sortNo;

    private Integer viewCount;

    /** 1上架 0下架 */
    private Integer status;

    /** 插入时自动填充 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 插入和更新时都自动填充 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private String createBy;

    private String updateBy;

    /** 逻辑删除字段：MP 查询会自动追加 AND deleted=0，删除变 UPDATE */
    @TableLogic
    private Integer deleted;

    /** 乐观锁版本号：更新时自动校验并自增 */
    @Version
    private Integer version;
}
