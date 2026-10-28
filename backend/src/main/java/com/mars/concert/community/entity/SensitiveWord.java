package com.mars.concert.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 敏感词实体，对应 cm_sensitive_word 表。
 * 词库由 DFA 过滤器启动时加载，支持后台运营增删。
 */
@Data
@TableName("cm_sensitive_word")
public class SensitiveWord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String word;
    /** 1启用 0停用 */
    private Integer status;
}
