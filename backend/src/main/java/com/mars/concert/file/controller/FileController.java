package com.mars.concert.file.controller;

import com.mars.concert.common.result.Result;
import com.mars.concert.common.web.CurrentUser;
import com.mars.concert.file.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "文件")
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @Operation(summary = "上传文件到公开桶，返回可访问URL")
    @PostMapping("/upload")
    public Result<String> upload(@CurrentUser Long userId,
                                 @RequestParam("file") MultipartFile file) {
        return Result.success(fileService.uploadPublic(file));
    }

    @Operation(summary = "私有桶对象生成限时预签名下载URL")
    @GetMapping("/presign")
    public Result<String> presign(@CurrentUser Long userId, @RequestParam String object) {
        return Result.success(fileService.presignedGet(object));
    }
}
