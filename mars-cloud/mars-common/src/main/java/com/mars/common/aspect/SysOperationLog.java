package com.mars.common.aspect;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 操作日志实体，对应 sys_operation_log 表 */
@Data
@TableName("sys_operation_log")
public class SysOperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String module;
    private String action;
    private String bizId;
    private String operator;
    private String params;
    /** 1成功 0失败 */
    private Integer success;
    private String errorMsg;
    private Integer costMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
