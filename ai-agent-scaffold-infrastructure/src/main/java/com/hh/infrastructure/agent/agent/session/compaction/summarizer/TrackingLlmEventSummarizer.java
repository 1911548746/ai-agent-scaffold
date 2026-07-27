package com.hh.infrastructure.agent.agent.session.compaction.summarizer;

import com.google.adk.events.Event;
import com.google.adk.summarizer.BaseEventSummarizer;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.StringList;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupPlan;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicy;
import io.reactivex.rxjava3.core.Maybe;

import java.util.ArrayList;
import java.util.List;

/**
 * 为 ADK 默认 LLM 摘要补充精确的源 Event 身份。
 *
 * <p>ADK 会将本轮实际参与压缩的 Event 列表传入 {@link BaseEventSummarizer}，但其标准
 * {@code EventCompaction} 仅携带时间范围。清理策略先从该列表区分“已覆盖”与“必须保留”的 Event，
 * 再把可删除 ID 写入摘要 Event 的内部 metadata。</p>
 */
public final class TrackingLlmEventSummarizer implements BaseEventSummarizer {

    /** 仅供基础设施识别的内部 metadata key，不作为 Agent state 或对外 API 契约。 */
    public static final String DELETABLE_EVENT_IDS_METADATA_KEY = "scaffold.deletable-event-ids";

    private final BaseEventSummarizer delegate;
    private final CompactionCleanupPolicy cleanupPolicy;
    private final CompactionCleanupContext cleanupContext;

    public TrackingLlmEventSummarizer(
            BaseEventSummarizer delegate,
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        this.delegate = delegate;
        this.cleanupPolicy = cleanupPolicy;
        this.cleanupContext = cleanupContext;
    }

    @Override
    public Maybe<Event> summarizeEvents(List<Event> events) {
        // 删除集合必须由具体模式规划，不能把整个摘要输入直接当作可删除 Event。
        CompactionCleanupPlan cleanupPlan = cleanupPolicy.plan(events, cleanupContext);

        return delegate.summarizeEvents(events)
                .map(summaryEvent -> attachDeletableEventIds(summaryEvent, cleanupPlan.deletableEventIds()));
    }

    private Event attachDeletableEventIds(Event summaryEvent, List<String> deletableEventIds) {
        List<CustomMetadata> metadata = new ArrayList<>(summaryEvent.customMetadata().orElse(List.of()));
        // 避免重试或嵌套包装器留下同名 metadata，存储层只需要唯一的一组精确删除范围。
        metadata.removeIf(item -> DELETABLE_EVENT_IDS_METADATA_KEY.equals(item.key().orElse(null)));
        metadata.add(CustomMetadata.builder()
                .key(DELETABLE_EVENT_IDS_METADATA_KEY)
                .stringListValue(StringList.builder().values(deletableEventIds).build())
                .build());
        summaryEvent.setCustomMetadata(metadata);
        return summaryEvent;
    }
}
