package com.hh.config.agent.attachment;

import com.google.adk.artifacts.BaseArtifactService;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.infrastructure.agent.agent.artifact.MinioAttachmentStorageSettings;
import com.hh.infrastructure.agent.agent.artifact.MinioArtifactService;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


/** 创建唯一的 MinIO 客户端，并在启动阶段确保专用 bucket 存在。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentStorageProperties.class)
@ConditionalOnProperty(prefix = "ai.agent.attachment", name = "enabled", havingValue = "true")
public class AttachmentStorageConfiguration {

    @Bean
    public MinioClient minioClient(AttachmentStorageProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(properties.getBucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.getBucket()).build());
            }
        } catch (Exception error) {
            throw new IllegalStateException("attachment storage is unavailable", error);
        }
        return client;
    }

    @Bean
    public MinioAttachmentStorageSettings minioAttachmentStorageSettings(AttachmentStorageProperties properties) {
        return new MinioAttachmentStorageSettings(properties.getBucket(), properties.getTtl());
    }

    /**
     * 在 MinIO 客户端已经创建后显式组装附件端口。
     *
     * <p>适配器不使用组件扫描，避免 {@code ConditionalOnBean} 依赖配置类 Bean 的处理顺序，
     * 也确保应用上下文中始终只有一个 {@code AttachmentStoragePort} 实现。</p>
    */
    @Bean
    public MinioArtifactService minioArtifactService(
            MinioClient minioClient,
            AttachmentObjectDao attachmentObjectDao,
            MinioAttachmentStorageSettings settings) {
        return new MinioArtifactService(minioClient, attachmentObjectDao, settings);
    }

    @Bean
    public AttachmentStoragePort attachmentStoragePort(MinioArtifactService minioArtifactService) {
        return minioArtifactService;
    }

    @Bean
    public BaseArtifactService artifactService(MinioArtifactService minioArtifactService) {
        return minioArtifactService;
    }

}
