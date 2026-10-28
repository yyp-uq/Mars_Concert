package com.mars.concert.file.enums;

import com.mars.concert.common.enums.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 文件域错误码：80xx */
@Getter
@AllArgsConstructor
public enum FileErrorCode implements ErrorCode {
    FILE_UPLOAD_FAIL(8001, "文件上传失败"),
    PRESIGN_FAIL(8002, "生成下载链接失败"),
    FILE_EMPTY(8003, "文件不能为空");

    private final int code;
    private final String message;
}
