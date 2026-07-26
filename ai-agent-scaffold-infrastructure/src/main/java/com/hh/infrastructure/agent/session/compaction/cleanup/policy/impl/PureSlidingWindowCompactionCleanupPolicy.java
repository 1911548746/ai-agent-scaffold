package com.hh.infrastructure.agent.session.compaction.cleanup.policy.impl;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.session.compaction.cleanup.CompactionCleanupPlan;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.CompactionCleanupPolicy;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * pureSlidingWindow 的精确删除策略。
 *
 * <p>marker 已经让 ADK 忽略窗口前缀；本策略把同一前缀的 Event ID 写入 metadata，供 MySQL 在 marker
 * 成功落库后物理删除。与摘要型 slidingWindow 不同，无 invocationId 的旧 Event 也随前缀删除。</p>
 */
@Component
public class PureSlidingWindowCompactionCleanupPolicy implements CompactionCleanupPolicy {

    @Override
    public String type() {
        return ContextCompressTypeEnum.PURE_SLIDING_WINDOW.getType();
    }

    @Override
    public CompactionCleanupPlan plan(List<Event> eventsToSummarize, CompactionCleanupContext context) {
        int retainedStart = retainedStart(eventsToSummarize, context.overlapSize());
        if (retainedStart <= 0) {
            return CompactionCleanupPlan.none();
        }

        List<String> deletableIds = new ArrayList<>();
        for (int index = 0; index < retainedStart; index++) {
            String eventId = eventsToSummarize.get(index).id();
            if (eventId != null && !eventId.isBlank()) {
                deletableIds.add(eventId);
            }
        }
        return new CompactionCleanupPlan(deletableIds.stream().distinct().toList());
    }

    private int retainedStart(List<Event> events, Integer retentionSize) {
        int resolvedRetentionSize = retentionSize == null ? 0 : Math.max(0, retentionSize);
        if (resolvedRetentionSize < 1 || events.isEmpty()) {
            return 0;
        }

        Set<String> invocationIds = new LinkedHashSet<>();
        for (Event event : events) {
            if (event.invocationId() != null && !event.invocationId().isBlank()) {
                invocationIds.add(event.invocationId());
            }
        }
        if (invocationIds.size() <= resolvedRetentionSize) {
            return 0;
        }

        List<String> orderedInvocationIds = new ArrayList<>(invocationIds);
        String firstRetainedInvocationId = orderedInvocationIds.get(
                orderedInvocationIds.size() - resolvedRetentionSize);
        for (int index = 0; index < events.size(); index++) {
            if (firstRetainedInvocationId.equals(events.get(index).invocationId())) {
                return index;
            }
        }
        return 0;
    }
}
