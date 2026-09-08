package com.hh.application.agent.model;

import java.time.Instant;

/**
 * Runtime 发布尝试的脱敏历史记录。
 *
 * <p>记录只包含运维元数据，不保留配置正文、Definition、ADK 对象或异常堆栈。</p>
 */
public record RuntimePublication(
        long generation,
        String configDigest,
        RuntimeReloadTrigger trigger,
        Instant occurredAt,
        long durationMs,
        String outcome,
        String failureSummary) {
}
