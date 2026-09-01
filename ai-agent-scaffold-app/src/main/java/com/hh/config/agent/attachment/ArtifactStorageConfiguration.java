package com.hh.config.agent.attachment;

import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.infrastructure.agent.agent.artifact.config.ArtifactStorageDeploymentSettings;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageServiceFactory;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioArtifactCleanupJob;
import com.hh.infrastructure.agent.agent.artifact.store.minio.MinioLifecycleManager;
import com.hh.infrastructure.agent.agent.artifact.routing.RuntimeAttachmentStoragePort;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.infrastructure.agent.dao.AttachmentObjectDao;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 组装 Artifact 存储工厂和 Runtime 路由；本配置本身不会创建 MinIO 客户端。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArtifactStorageProperties.class)
public class ArtifactStorageConfiguration {

    @Bean
    public ArtifactStorageDeploymentSettings artifactStorageDeploymentSettings(
            ArtifactStorageProperties properties) {
        ArtifactStorageProperties.Minio minio = properties.getMinio();
        ArtifactStorageProperties.Local local = properties.getLocal();
        return new ArtifactStorageDeploymentSettings(
                new ArtifactStorageDeploymentSettings.Minio(
                        minio.getEndpoint(),
                        minio.getBucket(),
                        minio.getAccessKey(),
                        minio.getSecretKey(),
                        minio.getCleanupInterval()),
                new ArtifactStorageDeploymentSettings.Local(local.getRootDirectory()));
    }

    @Bean
    public MinioLifecycleManager minioLifecycleManager() {
        return new MinioLifecycleManager();
    }

    @Bean
    public ArtifactStorageServiceFactory artifactStorageServiceFactory(
            AttachmentObjectDao attachmentObjectDao,
            ArtifactStorageDeploymentSettings deploymentSettings,
            MinioLifecycleManager lifecycleManager) {
        return new ArtifactStorageServiceFactory(attachmentObjectDao, deploymentSettings, lifecycleManager);
    }

    @Bean
    public AttachmentStoragePort attachmentStoragePort(AdkAgentRuntimeRegistry runtimeRegistry) {
        return new RuntimeAttachmentStoragePort(runtimeRegistry);
    }

    @Bean
    public MinioArtifactCleanupJob minioArtifactCleanupJob(AdkAgentRuntimeRegistry runtimeRegistry) {
        return new MinioArtifactCleanupJob(runtimeRegistry);
    }
}
