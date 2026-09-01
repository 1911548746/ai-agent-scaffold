package com.hh.infrastructure.agent.agent.artifact.store.minio;

import java.time.Duration;

/** 单个 Application 的 MinIO Artifact 参数。 */
public record MinioArtifactStorageSettings(
        String appName,
        String bucket,
        Duration ttl,
        boolean lifecycleEnabled) {

    public String objectPrefix() {
        return "artifacts/" + appName + "/";
    }
}
