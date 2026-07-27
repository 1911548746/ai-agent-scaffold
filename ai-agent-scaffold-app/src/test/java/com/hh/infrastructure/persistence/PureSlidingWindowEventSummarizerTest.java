package com.hh.infrastructure.persistence;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 pureSlidingWindow 仅创建 ADK compaction marker，不调用模型，也不会错误覆盖保留窗口。
 */
class PureSlidingWindowEventSummarizerTest {

    @Test
    void returnsEmptyBeforeInvocationCountExceedsRetentionSize() {
        PureSlidingWindowEventSummarizer summarizer = new PureSlidingWindowEventSummarizer(2);

        assertThat(summarizer.summarizeEvents(List.of(
                event("round-1", "round-1", 10),
                event("round-2", "round-2", 20))).isEmpty().blockingGet()).isTrue();
    }

    @Test
    void createsEmptyMarkerOnlyForExpiredPrefix() {
        PureSlidingWindowEventSummarizer summarizer = new PureSlidingWindowEventSummarizer(2);

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
        assertThat(PureSlidingWindowEventSummarizer.isPureSlidingWindowMarker(marker)).isTrue();
    }

    @Test
    void trackingWrapperWritesExactExpiredEventIdsForMySqlCleanup() {
        TrackingLlmEventSummarizer summarizer = new TrackingLlmEventSummarizer(
                new PureSlidingWindowEventSummarizer(2),
                new PureSlidingWindowCompactionCleanupPolicy(),
                new CompactionCleanupContext(2, null));

        Event marker = summarizer.summarizeEvents(List.of(
                event("round-1", "round-1", 10),
                event("round-2", "round-2", 20),
                event("round-3", "round-3", 30))).blockingGet();

        List<String> deletableIds = marker.customMetadata().orElseThrow().stream()
                .filter(metadata -> TrackingLlmEventSummarizer.DELETABLE_EVENT_IDS_METADATA_KEY
                        .equals(metadata.key().orElse(null)))
                .flatMap(metadata -> metadata.stringListValue().stream())
                .flatMap(value -> value.values().stream())
                .flatMap(List::stream)
                .toList();
        assertThat(deletableIds).containsExactly("round-1");
    }

    private Event event(String id, String invocationId, long timestamp) {
        return Event.builder().id(id).invocationId(invocationId).timestamp(timestamp).build();
    }
}
