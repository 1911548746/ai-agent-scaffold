package com.hh.config.agent.attachment;

import com.google.adk.artifacts.BaseArtifactService;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.infrastructure.agent.agent.artifact.MinioAttachmentStorageSettings;
import com.hh.infrastructure.agent.agent.artifact.MinioArtifactCleanupJob;
import com.hh.infrastructure.agent.agent.artifact.MinioArtifactService;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import io.minio.BucketExistsArgs;
import io.minio.DeleteBucketLifecycleArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketLifecycleArgs;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;


/** 创建唯一的 MinIO 客户端，并在启动阶段确保专用 bucket 存在。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentStorageProperties.class)
@ConditionalOnProperty(prefix = "ai.agent.attachment", name = "enabled", havingValue = "true")
public class AttachmentStorageConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AttachmentStorageConfiguration.class);
    private static final String ARTIFACT_OBJECT_PREFIX = "artifacts/";

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
            validateDurations(properties);
            configureLifecycle(client, properties);
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

    @Bean
    public MinioArtifactCleanupJob minioArtifactCleanupJob(
            MinioArtifactService minioArtifactService,
            AttachmentStorageProperties properties) {
        return new MinioArtifactCleanupJob(minioArtifactService, properties.getCleanupInterval());
    }

    private void validateDurations(AttachmentStorageProperties properties) {
        if (properties.getTtl() == null || properties.getTtl().isZero() || properties.getTtl().isNegative()) {
            throw new IllegalArgumentException("attachment ttl must be positive");
        }
        if (properties.getCleanupInterval() == null
                || properties.getCleanupInterval().isZero()
                || properties.getCleanupInterval().isNegative()) {
            throw new IllegalArgumentException("attachment cleanup interval must be positive");
        }
    }

    /**
     * 为项目专用 Bucket 配置对象层兜底过期规则。
     *
     * <p>MinIO Lifecycle 以天为粒度；不能整天表示的 TTL 仍由应用的 expires_at 清理，不能向下取整
     * 导致对象早于数据库逻辑过期时间被删除。</p>
     */
    void configureLifecycle(MinioClient client, AttachmentStorageProperties properties) throws Exception {
        Duration ttl = properties.getTtl();
        long days = ttl.toDays();
        if (days < 1 || !ttl.equals(Duration.ofDays(days))) {
            // 配置改为非整天 TTL 时，必须清掉旧的 Lifecycle 规则，避免旧规则继续提前删除对象。
            client.deleteBucketLifecycle(DeleteBucketLifecycleArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
            log.info("attachment ttl is not a whole number of days; application cleanup remains authoritative");
            return;
        }
        if (days > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("attachment ttl is too large");
        }
        LifecycleRule rule = new LifecycleRule(
                Status.ENABLED,
                null,
                new Expiration((ZonedDateTime) null, (int) days, null),
                new RuleFilter(ARTIFACT_OBJECT_PREFIX),
                "attachment-expiration",
                null,
                null,
                null);
        client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                .bucket(properties.getBucket())
                .config(new LifecycleConfiguration(List.of(rule)))
                .build());
    }

}
