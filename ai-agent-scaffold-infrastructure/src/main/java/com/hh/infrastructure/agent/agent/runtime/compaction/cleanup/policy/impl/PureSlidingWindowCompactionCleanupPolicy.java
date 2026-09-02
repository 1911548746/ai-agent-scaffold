package com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.CompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.PureSlidingWindowBoundary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * pureSlidingWindow 的精确删除策略。
 *
 * <p>marker 已经让 ADK 忽略窗口前缀；本策略把同一前缀的 Event ID 写入 metadata，供 MySQL 在 marker
 * 成功落库后物理删除。与摘要型 slidingWindow 不同，无 invocationId 的旧 Event 也随前缀删除。</p>
 */
@Component
public class PureSlidingWindowCompactionCleanupPolicy implements CompactionCleanupPolicy {

    /** 使用统一窗口边界返回过期前缀的 Event ID，确保与 marker 指向同一段历史。 */
    @Override
    public CompactionCleanupPlan plan(List<Event> eventsToSummarize, CompactionCleanupContext context) {
        PureSlidingWindowBoundary boundary = PureSlidingWindowBoundary.resolve(
                eventsToSummarize, context.overlapSize());
        if (!boundary.hasExpiredEvents()) {
            return CompactionCleanupPlan.none();
        }
        return new CompactionCleanupPlan(boundary.expiredEventIds());
    }
}
