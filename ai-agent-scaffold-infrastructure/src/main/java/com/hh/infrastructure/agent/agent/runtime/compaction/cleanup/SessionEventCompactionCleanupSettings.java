package com.hh.infrastructure.agent.agent.runtime.compaction.cleanup;

/**
 * 压缩后 Event 清理的不可变运行参数。
 *
 * <p>该类型不依赖 Spring 配置绑定，避免 infrastructure 反向依赖 app；由 app 组合根在启动期创建。</p>
 */
public record SessionEventCompactionCleanupSettings(boolean deleteCompactedEvents) {
}
