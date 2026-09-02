package com.hh.infrastructure.agent.agent.runtime.compaction.summarizer;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.StringList;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.CompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.metadata.CompactionEventMetadata;
import com.hh.infrastructure.agent.agent.runtime.compaction.PureSlidingWindowBoundary;
import io.reactivex.rxjava3.core.Maybe;

import java.util.ArrayList;
import java.util.List;

/**
 * 不调用模型的纯滑动窗口 EventSummarizer。
 *
 * <p>ADK 的 SlidingWindowEventCompactor 负责按 invocation 选择窗口。本实现只为窗口前缀创建一个内容为空的
 * compaction marker；marker 的时间边界让 ADK 后续构造上下文时忽略过期 Event，而不会产生任何模型摘要。</p>
 */
public final class PureSlidingWindowEventSummarizer extends AbstractCompactionEventSummarizer {

    private final int invocationRetentionSize;

    /** 创建不依赖 LLM 的纯窗口摘要器，并保存需要保留的完整 invocation 数量。 */
    public PureSlidingWindowEventSummarizer(
            int invocationRetentionSize,
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        super(cleanupPolicy, cleanupContext);
        this.invocationRetentionSize = invocationRetentionSize;
    }

    /** 根据窗口边界生成空内容 marker；窗口未超限时返回空结果。 */
    @Override
    protected Maybe<Event> summarizeCompactionEvents(List<Event> events) {
        PureSlidingWindowBoundary boundary = PureSlidingWindowBoundary.resolve(
                events, invocationRetentionSize);
        if (!boundary.hasExpiredEvents()) {
            // 窗口尚未超过容量时不追加 marker，避免在首次对话就隐藏上下文。
            return Maybe.empty();
        }

        Event firstExpiredEvent = events.get(0);
        Event lastExpiredEvent = events.get(boundary.retainedStart() - 1);
        EventCompaction compaction = EventCompaction.builder()
                .startTimestamp(firstExpiredEvent.timestamp())
                .endTimestamp(lastExpiredEvent.timestamp())
                // 空内容表示过期前缀直接丢弃而不是被摘要替代；role 保持 ADK compaction 事件格式一致。
                .compactedContent(Content.builder().role("model").build())
                .build();

        return Maybe.just(Event.builder()
                .author("user")
                .invocationId(Event.generateEventId())
                .actions(EventActions.builder().compaction(compaction).build())
                .customMetadata(List.of(CustomMetadata.builder()
                        .key(CompactionEventMetadata.PURE_SLIDING_WINDOW_MARKER_KEY)
                        .stringListValue(StringList.builder().values(List.of("true")).build())
                        .build()))
                .build());
    }

}
