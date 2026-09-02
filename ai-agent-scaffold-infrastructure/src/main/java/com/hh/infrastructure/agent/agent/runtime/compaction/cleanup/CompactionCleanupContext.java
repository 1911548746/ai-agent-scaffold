package com.hh.infrastructure.agent.agent.runtime.compaction.cleanup;

/**
 * 一次摘要任务的保留参数。
 *
 * <p>策略只依赖这一不可变上下文和 ADK 传入的 Event 列表，不读取 Spring 配置，便于离线测试和后续
 * 新增压缩策略。</p>
 */
public record CompactionCleanupContext(
        Integer overlapSize,
        Integer eventRetentionSize) {
}
