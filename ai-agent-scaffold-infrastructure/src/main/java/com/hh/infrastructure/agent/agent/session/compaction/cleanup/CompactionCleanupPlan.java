package com.hh.infrastructure.agent.agent.session.compaction.cleanup;

import java.util.List;

/**
 * 摘要成功后可删除的持久化 Event 身份集合。
 */
public record CompactionCleanupPlan(
        List<String> deletableEventIds
) {
    public static CompactionCleanupPlan none() {
        return new CompactionCleanupPlan(List.of());
    }
}
