package com.mars.concert.file.service;

import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.file.config.MinioProps;
import com.mars.concert.file.enums.FileErrorCode;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 文件服务（手册 §9.3）：
 * 公开 bucket 直接上传返 URL；私有 bucket 发限时预签名下载链接。
 * 大文件/视频进阶做法：后端只发预签名 PUT URL，前端直传，流量不过应用服务器。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final MinioClient client;
    private final MinioProps props;

    /** 上传到公开 bucket，返回可访问 URL */
    public String uploadPublic(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(FileErrorCode.FILE_EMPTY);
        }
        try {
            String object = "upload/" + LocalDate.now() + "/"
                    + UUID.randomUUID() + extOf(file.getOriginalFilename());
            ensureBucket(props.getBucketPublic());
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucketPublic()).object(object)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
            return props.getEndpoint() + "/" + props.getBucketPublic() + "/" + object;
        } catch (Exception e) {
            log.error("文件上传失败 name={}", file.getOriginalFilename(), e);
            throw new BusinessException(FileErrorCode.FILE_UPLOAD_FAIL.getCode(),
                    FileErrorCode.FILE_UPLOAD_FAIL.getMessage() + ": " + e.getMessage());
        }
    }

    /** 私有 bucket 生成限时签名下载 URL（票品附件/发票等，10 分钟有效） */
    public String presignedGet(String object) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(props.getBucketPrivate()).object(object)
                    .expiry(10, TimeUnit.MINUTES).build());
        } catch (Exception e) {
            log.error("生成预签名下载链接失败 object={}", object, e);
            throw new BusinessException(FileErrorCode.PRESIGN_FAIL);
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    /** 提取扩展名（含点号），无扩展名返回空串 */
    private String extOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot);
    }
}
