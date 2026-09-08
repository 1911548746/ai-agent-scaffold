package com.hh.application.agent.model;

import java.time.Instant;

/**
 * Agent Runtime 的脱敏运行状态。
 *
 * <p>该模型只包含运维所需的不可变元数据，不携带 Runner、模型配置、工具实例或会话内容。</p>
 */
public record RuntimeStatus(
        long generation,
        String configDigest,
        int runtimeCount,
        Instant publishedAt,
        long reloadSuccessCount,
        long reloadFailureCount,
        Long lastCompileDurationMs,
        String lastFailureSummary) {

    public static RuntimeStatus initial() {
        return new RuntimeStatus(0, null, 0, null, 0, 0, null, null);
    }

    public RuntimeStatus published(long generation, String digest, int count, Instant publishedAt) {
        return new RuntimeStatus(generation, digest, count, publishedAt,
                reloadSuccessCount, reloadFailureCount, lastCompileDurationMs, lastFailureSummary);
    }

    public RuntimeStatus cleared(long generation) {
        return new RuntimeStatus(generation, null, 0, publishedAt,
                reloadSuccessCount, reloadFailureCount, lastCompileDurationMs, lastFailureSummary);
    }

    public RuntimeStatus compiled(long durationMs) {
        return new RuntimeStatus(generation, configDigest, runtimeCount, publishedAt,
                reloadSuccessCount, reloadFailureCount, durationMs, lastFailureSummary);
    }

    public RuntimeStatus reloadSucceeded() {
        return new RuntimeStatus(generation, configDigest, runtimeCount, publishedAt,
                reloadSuccessCount + 1, reloadFailureCount, lastCompileDurationMs, lastFailureSummary);
    }

    public RuntimeStatus reloadFailed(String summary, long durationMs) {
        return new RuntimeStatus(generation, configDigest, runtimeCount, publishedAt,
                reloadSuccessCount, reloadFailureCount + 1, durationMs, summary);
    }

    public RuntimeStatus startupFailed(String summary) {
        return new RuntimeStatus(generation, configDigest, runtimeCount, publishedAt,
                reloadSuccessCount, reloadFailureCount, lastCompileDurationMs, summary);
    }
}
