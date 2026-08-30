package com.hh.infrastructure.agent.agent.artifact;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

/**
 * 附件元数据清理任务。
 *
 * <p>MinIO Lifecycle 负责对象层兜底删除，本任务负责依据数据库精确的 expires_at 清理元数据，
 * 并在 Lifecycle 未覆盖或删除延迟时重试对象删除。</p>
 */
@Slf4j
public class MinioArtifactCleanupJob {

    private static final int CLEANUP_BATCH_SIZE = 100;

    private final MinioArtifactService artifactService;
    private final Duration cleanupInterval;

    public MinioArtifactCleanupJob(MinioArtifactService artifactService, Duration cleanupInterval) {
        this.artifactService = artifactService;
        this.cleanupInterval = cleanupInterval;
    }

    /** 使用配置的固定延迟，避免任务失败时形成高频重试。 */
    @Scheduled(fixedDelayString = "${ai.agent.attachment.cleanup-interval:1h}")
    public void cleanupExpiredArtifacts() {
        try {
            int deleted = artifactService.cleanupExpired(CLEANUP_BATCH_SIZE);
            if (deleted > 0) {
                log.info("expired attachment cleanup completed, deletedMetadataCount={}", deleted);
            }
        } catch (RuntimeException error) {
            // 保留数据库记录，下一轮继续重试；日志不包含文件名、用户或 Session 身份。
            log.warn("expired attachment cleanup failed, retryInterval={}", cleanupInterval, error);
        }
    }
}
