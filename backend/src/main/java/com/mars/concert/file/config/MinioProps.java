package com.mars.concert.file.config;

import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO 连接配置（手册 §9.2/§9.3） */
@Configuration
@ConfigurationProperties(prefix = "mars.minio")
@Data
public class MinioProps {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketPublic;
    private String bucketPrivate;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
