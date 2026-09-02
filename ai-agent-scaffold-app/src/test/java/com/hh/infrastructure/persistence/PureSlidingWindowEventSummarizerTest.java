package com.hh.infrastructure.persistence;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.metadata.CompactionEventMetadata;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.PureSlidingWindowEventSummarizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 pureSlidingWindow 仅创建 ADK compaction marker，不调用模型，也不会错误覆盖保留窗口。
 */
class PureSlidingWindowEventSummarizerTest {

    @Test
    void returnsEmptyBeforeInvocationCountExceedsRetentionSize() {
        PureSlidingWindowEventSummarizer summarizer = summarizer(2);

        assertThat(summarizer.summarizeEvents(List.of(
                event("round-1", "round-1", 10),
                event("round-2", "round-2", 20))).isEmpty().blockingGet()).isTrue();
    }

    @Test
    void createsEmptyMarkerOnlyForExpiredPrefix() {
        PureSlidingWindowEventSummarizer summarizer = summarizer(2);

        Event marker = summarizer.summarizeEvents(List.of(
                event("round-1", "round-1", 10),
                event("round-2", "round-2", 20),
                event("round-3", "round-3", 30))).blockingGet();

        assertThat(marker.actions().compaction()).isPresent();
        assertThat(marker.actions().compaction().orElseThrow())
                .extracting(compaction -> compaction.startTimestamp(), compaction -> compaction.endTimestamp())
                .containsExactly(10L, 10L);
        assertThat(marker.actions().compaction().orElseThrow().compactedContent().role())
                .contains("model");
        assertThat(CompactionEventMetadata.isPureSlidingWindowMarker(marker)).isTrue();
    }

    @Test
    void pureSlidingWindowSummarizerWritesExactExpiredEventIdsForMySqlCleanup() {
        PureSlidingWindowEventSummarizer summarizer = summarizer(2);

        Event marker = summarizer.summarizeEvents(List.of(
                event("round-1", "round-1", 10),
                event("round-2", "round-2", 20),
                event("round-3", "round-3", 30))).blockingGet();

        List<String> deletableIds = marker.customMetadata().orElseThrow().stream()
                .filter(metadata -> CompactionEventMetadata.DELETABLE_EVENT_IDS_KEY
                        .equals(metadata.key().orElse(null)))
                .flatMap(metadata -> metadata.stringListValue().stream())
                .flatMap(value -> value.values().stream())
                .flatMap(List::stream)
                .toList();
        assertThat(deletableIds).containsExactly("round-1");
    }

    private PureSlidingWindowEventSummarizer summarizer(int retentionSize) {
        return new PureSlidingWindowEventSummarizer(
                retentionSize,
                new PureSlidingWindowCompactionCleanupPolicy(),
                new CompactionCleanupContext(retentionSize, null));
    }

    private Event event(String id, String invocationId, long timestamp) {
        return Event.builder().id(id).invocationId(invocationId).timestamp(timestamp).build();
    }
}
