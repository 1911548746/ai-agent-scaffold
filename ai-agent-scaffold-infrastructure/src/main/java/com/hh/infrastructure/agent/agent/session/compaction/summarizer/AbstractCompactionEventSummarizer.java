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
 * 压缩策略专属摘要器的公共模板。
 *
 * <p>子类只决定如何将本轮 Event 转换为摘要或 marker。公共流程根据 ADK 实际交给摘要器的 Event 计算可删除
 * 集合，并将精确 Event ID 写入输出 metadata；Session Store 在输出 Event 成功持久化后才据此删除源 Event。</p>
 */
public abstract class AbstractCompactionEventSummarizer implements BaseEventSummarizer {

    /** 仅供基础设施识别的内部 metadata key，不作为 Agent state 或对外 API 契约。 */
    public static final String DELETABLE_EVENT_IDS_METADATA_KEY = "scaffold.deletable-event-ids";

    private final CompactionCleanupPolicy cleanupPolicy;
    private final CompactionCleanupContext cleanupContext;

    protected AbstractCompactionEventSummarizer(
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        this.cleanupPolicy = cleanupPolicy;
        this.cleanupContext = cleanupContext;
    }

    /**
     * 摘要器的入口方法。
     */
    @Override
    public final Maybe<Event> summarizeEvents(List<Event> events) {
        // 根据不同的压缩策略，生成待删除 Event ID 集合
        CompactionCleanupPlan cleanupPlan = cleanupPolicy.plan(events, cleanupContext);

        // 调用子类方法生成摘要 Event
        // 子类可以返回空结果，例如 pureSlidingWindow 的窗口尚未超过容量时不产生 marker。
        Maybe<Event> rawSummarizedEvents = summarizeCompactionEvents(events);
        // 将待删除 Event ID 集合写入摘要 Event 的 metadata，供 Session Store 删除源 Event 时精确指定。
        Maybe<Event> summarizedEvents = rawSummarizedEvents.map(
                summaryEvent -> attachDeletableEventIds(summaryEvent, cleanupPlan.deletableEventIds()));
        return summarizedEvents;
    }

    /**
     * 生成摘要 Event，具体实现由子类完成。
     */
    protected abstract Maybe<Event> summarizeCompactionEvents(List<Event> events);

    /**
     * 将待删除 Event ID 集合写入摘要 Event 的 metadata。
     */
    private Event attachDeletableEventIds(Event summaryEvent, List<String> deletableEventIds) {
        List<CustomMetadata> metadata = new ArrayList<>(summaryEvent.customMetadata().orElse(List.of()));
        // 避免重试或嵌套处理留下同名 metadata，存储层只需要唯一的一组精确删除范围。
        metadata.removeIf(item -> DELETABLE_EVENT_IDS_METADATA_KEY.equals(item.key().orElse(null)));
        metadata.add(CustomMetadata.builder()
                .key(DELETABLE_EVENT_IDS_METADATA_KEY)
                .stringListValue(StringList.builder().values(deletableEventIds).build())
                .build());
        summaryEvent.setCustomMetadata(metadata);
        return summaryEvent;
    }
}
