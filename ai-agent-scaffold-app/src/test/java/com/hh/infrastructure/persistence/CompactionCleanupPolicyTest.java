package com.hh.infrastructure.persistence;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.SlidingWindowCompactionCleanupPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固化两种 ADK 压缩模式的删除边界，避免 Session 存储层再次按行号或时间戳猜测 overlap。
 */
class CompactionCleanupPolicyTest {

    private final SlidingWindowCompactionCleanupPolicy slidingPolicy = new SlidingWindowCompactionCleanupPolicy();
    private final FixedSizeCompactionCleanupPolicy fixedPolicy = new FixedSizeCompactionCleanupPolicy();

    @Test
    void slidingWindowRetainsLastOverlapInvocationAndAllPreviousSummaries() {
        List<Event> secondCompactionInput = List.of(
                compactionEvent("summary-a"),
                event("round-3-user", "round-3"), event("round-3-ai", "round-3"),
                event("round-4-user", "round-4"), event("round-4-ai", "round-4"),
                event("round-5-user", "round-5"), event("round-5-ai", "round-5"),
                event("round-6-user", "round-6"), event("round-6-ai", "round-6"));

        List<String> deletableIds = slidingPolicy.plan(
                        secondCompactionInput, new CompactionCleanupContext(1, null))
                .deletableEventIds();

        // 摘要 A 是独立历史区间，round-6 是下一窗口 overlap；二者均不能删除。
        assertThat(deletableIds).containsExactly(
                "round-3-user", "round-3-ai",
                "round-4-user", "round-4-ai",
                "round-5-user", "round-5-ai");
    }

    @Test
    void slidingWindowKeepsEventsWithoutInvocationIdConservatively() {
        List<String> deletableIds = slidingPolicy.plan(List.of(
                        event("round-1", "round-1"),
                        eventWithoutInvocation("system-event"),
                        event("round-2", "round-2")), new CompactionCleanupContext(1, null))
                .deletableEventIds();

        assertThat(deletableIds).containsExactly("round-1");
    }

    @Test
    void fixedSizeDeletesPriorSummaryBecauseNewSummaryAlreadyContainsIt() {
        List<String> deletableIds = fixedPolicy.plan(List.of(
                        compactionEvent("summary-a"),
                        event("old-user", "old-round"),
                        event("old-ai", "old-round")), new CompactionCleanupContext(null, 30))
                .deletableEventIds();

        assertThat(deletableIds).containsExactly("summary-a", "old-user", "old-ai");
    }

    private Event event(String id, String invocationId) {
        return Event.builder().id(id).invocationId(invocationId).build();
    }

    private Event eventWithoutInvocation(String id) {
        return Event.builder().id(id).build();
    }

    private Event compactionEvent(String id) {
        return Event.builder()
                .id(id)
                .actions(EventActions.builder()
                        .compaction(EventCompaction.builder()
                                .startTimestamp(1L)
                                .endTimestamp(2L)
                                .compactedContent(com.google.genai.types.Content.builder().build())
                                .build())
                        .build())
                .build();
    }
}
