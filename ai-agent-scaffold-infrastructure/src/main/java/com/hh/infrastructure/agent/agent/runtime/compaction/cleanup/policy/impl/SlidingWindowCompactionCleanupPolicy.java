package com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.CompactionCleanupPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sliding window 的删除策略。
 *
 * <p>ADK 会再次把 overlap invocation 的原始 Event 交给下一次摘要，因此只能删除更早的原始 Event；
 * 每个 compaction 摘要独立代表一段历史，绝不能在该策略下删除。</p>
 */
@Component
public class SlidingWindowCompactionCleanupPolicy implements CompactionCleanupPolicy {

    /** 保留最近 overlap 个完整 invocation，并删除更早的普通 Event。 */
    @Override
    public CompactionCleanupPlan plan(List<Event> eventsToSummarize, CompactionCleanupContext context) {
        Map<String, List<Event>> eventsByInvocation = new LinkedHashMap<>();
        for (Event event : eventsToSummarize) {
            // 摘要本身代表独立历史分段；没有 invocationId 的 Event 无法可靠归属轮次，因此保守保留。
            if (isCompactionEvent(event) || event.invocationId() == null || event.invocationId().isBlank()) {
                continue;
            }
            eventsByInvocation.computeIfAbsent(event.invocationId(), ignored -> new ArrayList<>()).add(event);
        }

        int overlapSize = context.overlapSize() == null ? 0 : Math.max(0, context.overlapSize());
        List<String> invocationIds = new ArrayList<>(eventsByInvocation.keySet());
        int retainedStart = Math.max(0, invocationIds.size() - overlapSize);
        Set<String> retainedInvocations = new LinkedHashSet<>(invocationIds.subList(retainedStart, invocationIds.size()));

        List<String> deletableIds = new ArrayList<>();
        for (Map.Entry<String, List<Event>> entry : eventsByInvocation.entrySet()) {
            if (!retainedInvocations.contains(entry.getKey())) {
                entry.getValue().stream()
                        .map(Event::id)
                        .filter(this::hasText)
                        .forEach(deletableIds::add);
            }
        }
        return new CompactionCleanupPlan(deletableIds.stream().distinct().toList());
    }

    private boolean isCompactionEvent(Event event) {
        // compaction Event 不是普通对话 invocation，不能作为窗口前缀删除。
        return event.actions() != null && event.actions().compaction().isPresent();
    }

    private boolean hasText(String value) {
        // 删除操作只接受真实 Event ID，避免把空值传入批量 SQL。
        return value != null && !value.isBlank();
    }
}
