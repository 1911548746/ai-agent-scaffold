package com.hh.infrastructure.agent.agent.artifact.store.minio;

import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.infrastructure.agent.agent.runtime.RuntimeLease;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/** 按已发布 Runtime 清理配置了精确 TTL 的 MinIO Artifact。 */
@Slf4j
public class MinioArtifactCleanupJob {

    private static final int CLEANUP_BATCH_SIZE = 100;

    private final AdkAgentRuntimeRegistry runtimeRegistry;

    public MinioArtifactCleanupJob(AdkAgentRuntimeRegistry runtimeRegistry) {
        this.runtimeRegistry = runtimeRegistry;
    }

    @Scheduled(fixedDelayString = "${ai.agent.artifact-storage.minio.cleanup-interval:1h}")
    public void cleanupExpiredArtifacts() {
        int deleted = 0;
        try {
            for (RuntimeLease lease : runtimeRegistry.acquireAll()) {
                try (lease) {
                    var runtime = lease.runtime();
                    if (runtime.runner().artifactService() instanceof MinioArtifactService service
                            && service.ttl() != null) {
                        deleted += service.cleanupExpired(CLEANUP_BATCH_SIZE);
                    }
                }
            }
            if (deleted > 0) {
                log.info("expired attachment cleanup completed, deletedMetadataCount={}", deleted);
            }
        } catch (RuntimeException error) {
            // 对象删除失败时元数据仍在，下一次调度会继续重试。
            log.warn("expired attachment cleanup failed", error);
        }
    }
}
