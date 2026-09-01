package com.hh.infrastructure.agent.agent.artifact;

import com.hh.domain.agent.model.definition.ArtifactStorageDefinition;
import com.hh.domain.agent.model.enums.ArtifactStorageType;
import com.hh.infrastructure.agent.agent.artifact.config.ArtifactStorageDeploymentSettings;
import com.hh.infrastructure.agent.agent.artifact.store.local.LocalArtifactService;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioArtifactService;
import com.hh.infrastructure.agent.agent.artifact.store.memory.MemoryArtifactService;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioArtifactStorageSettings;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioLifecycleManager;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;

/** 按已校验的 Runner Definition 创建每个 Runtime 独占的 ArtifactService。 */
public class ArtifactStorageServiceFactory {

    private final AttachmentObjectDao attachmentObjectDao;
    private final ArtifactStorageDeploymentSettings deploymentSettings;
    private final MinioLifecycleManager lifecycleManager;
    private volatile MinioClient minioClient;

    public ArtifactStorageServiceFactory(
            AttachmentObjectDao attachmentObjectDao,
            ArtifactStorageDeploymentSettings deploymentSettings,
            MinioLifecycleManager lifecycleManager) {
        this.attachmentObjectDao = attachmentObjectDao;
        this.deploymentSettings = deploymentSettings;
        this.lifecycleManager = lifecycleManager;
    }

    public ArtifactStorageService create(String appName, ArtifactStorageDefinition definition) {
        ArtifactStorageDefinition configured = definition == null ? new ArtifactStorageDefinition() : definition;
        ArtifactStorageType type = configured.getType() == null ? ArtifactStorageType.LOCAL : configured.getType();
        return switch (type) {
            case MEMORY -> new MemoryArtifactService(appName);
            case LOCAL -> new LocalArtifactService(appName, requireLocalRoot(), attachmentObjectDao);
            case MINIO -> new MinioArtifactService(
                    requireMinioClient(),
                    attachmentObjectDao,
                    new MinioArtifactStorageSettings(
                            appName,
                            deploymentSettings.minio().bucket(),
                            configured.getTtl(),
                            configured.isLifecycleEnabled()));
        };
    }

    /** 所有候选 Runtime 编译完成后统一对账，失败时 Registry 仍保留旧快照。 */
    public void reconcileLifecycle(Collection<AdkAgentRuntime> runtimes) {
        List<MinioArtifactStorageSettings> settings = runtimes.stream()
                .map(runtime -> runtime.runner().artifactService())
                .filter(MinioArtifactService.class::isInstance)
                .map(MinioArtifactService.class::cast)
                .map(MinioArtifactService::settings)
                .toList();
        if (settings.isEmpty()) {
            return;
        }
        lifecycleManager.reconcile(requireMinioClient(), deploymentSettings.minio().bucket(), settings);
    }

    private Path requireLocalRoot() {
        ArtifactStorageDeploymentSettings.Local local = deploymentSettings.local();
        if (local == null || local.rootDirectory() == null) {
            throw new IllegalStateException("local artifact root-directory is required");
        }
        return local.rootDirectory();
    }

    private MinioClient requireMinioClient() {
        MinioClient current = minioClient;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (minioClient == null) {
                ArtifactStorageDeploymentSettings.Minio settings = validateMinioSettings();
                MinioClient candidate = MinioClient.builder()
                        .endpoint(settings.endpoint())
                        .credentials(settings.accessKey(), settings.secretKey())
                        .build();
                try {
                    if (!candidate.bucketExists(BucketExistsArgs.builder()
                            .bucket(settings.bucket()).build())) {
                        candidate.makeBucket(MakeBucketArgs.builder().bucket(settings.bucket()).build());
                    }
                } catch (Exception error) {
                    throw new IllegalStateException("minio artifact storage is unavailable", error);
                }
                minioClient = candidate;
            }
            return minioClient;
        }
    }

    private ArtifactStorageDeploymentSettings.Minio validateMinioSettings() {
        ArtifactStorageDeploymentSettings.Minio settings = deploymentSettings.minio();
        if (settings == null
                || isBlank(settings.endpoint())
                || isBlank(settings.bucket())
                || isBlank(settings.accessKey())
                || isBlank(settings.secretKey())) {
            throw new IllegalStateException("minio endpoint, bucket, access-key and secret-key are required");
        }
        Duration interval = settings.cleanupInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalStateException("minio cleanup-interval must be positive");
        }
        return settings;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
