package com.hh.infrastructure.agent.agent.artifact.config;

import java.nio.file.Path;
import java.time.Duration;

/** app 层部署配置转换后的基础设施参数；只有选中对应后端时才会校验和使用。 */
public record ArtifactStorageDeploymentSettings(
        Minio minio,
        Local local) {

    public record Minio(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey,
            Duration cleanupInterval) {
    }

    public record Local(Path rootDirectory) {
    }
}
