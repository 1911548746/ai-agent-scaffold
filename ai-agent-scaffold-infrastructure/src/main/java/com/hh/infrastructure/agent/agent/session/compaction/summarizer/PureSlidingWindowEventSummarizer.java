package com.hh.infrastructure.agent.agent.session.compaction.summarizer;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.genai.types.Content;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.StringList;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicy;
import io.reactivex.rxjava3.core.Maybe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 不调用模型的纯滑动窗口 EventSummarizer。
 *
 * <p>ADK 的 SlidingWindowEventCompactor 负责按 invocation 选择窗口。本实现只为窗口前缀创建一个内容为空的
 * compaction marker；marker 的时间边界让 ADK 后续构造上下文时忽略过期 Event，而不会产生任何模型摘要。</p>
 */
public final class PureSlidingWindowEventSummarizer extends AbstractCompactionEventSummarizer {

    /** 仅供 MySQL Store 判断“可在物理删除源 Event 后省略落库”的内部 marker。 */
    public static final String PURE_SLIDING_WINDOW_MARKER_METADATA_KEY = "scaffold.pure-sliding-window-marker";

    private final int invocationRetentionSize;

    public PureSlidingWindowEventSummarizer(
            int invocationRetentionSize,
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        super(cleanupPolicy, cleanupContext);
        this.invocationRetentionSize = invocationRetentionSize;
    }

    @Override
    protected Maybe<Event> summarizeCompactionEvents(List<Event> events) {
        int retainedStart = retainedStart(events);
        if (retainedStart <= 0) {
            // 窗口尚未超过容量时不追加 marker，避免在首次对话就隐藏上下文。
            return Maybe.empty();
        }

        Event firstExpiredEvent = events.get(0);
        Event lastExpiredEvent = events.get(retainedStart - 1);
        EventCompaction compaction = EventCompaction.builder()
                .startTimestamp(firstExpiredEvent.timestamp())
                .endTimestamp(lastExpiredEvent.timestamp())
                // 空内容是“直接丢弃”而不是“摘要替代”；role 保持与 ADK 默认摘要输出一致。
                .compactedContent(Content.builder().role("model").build())
                .build();

        return Maybe.just(Event.builder()
                .author("user")
                .invocationId(Event.generateEventId())
                .actions(EventActions.builder().compaction(compaction).build())
                .customMetadata(List.of(CustomMetadata.builder()
                        .key(PURE_SLIDING_WINDOW_MARKER_METADATA_KEY)
                        .stringListValue(StringList.builder().values(List.of("true")).build())
                        .build()))
                .build());
    }

    /**
     * 该标记不属于 ADK 协议，只用于区分“必须保存的模型摘要”和“源 Event 删除后可以省略的空 marker”。
     */
    public static boolean isPureSlidingWindowMarker(Event event) {
        return event.customMetadata().orElse(List.of()).stream()
                .anyMatch(metadata -> PURE_SLIDING_WINDOW_MARKER_METADATA_KEY
                        .equals(metadata.key().orElse(null)));
    }

    private int retainedStart(List<Event> events) {
        if (invocationRetentionSize < 1 || events.isEmpty()) {
            return 0;
        }

        Set<String> invocationIds = new LinkedHashSet<>();
        for (Event event : events) {
            if (hasText(event.invocationId())) {
                invocationIds.add(event.invocationId());
            }
        }
        if (invocationIds.size() <= invocationRetentionSize) {
            return 0;
        }

        List<String> orderedInvocationIds = new ArrayList<>(invocationIds);
        String firstRetainedInvocationId = orderedInvocationIds.get(
                orderedInvocationIds.size() - invocationRetentionSize);
        for (int index = 0; index < events.size(); index++) {
            if (firstRetainedInvocationId.equals(events.get(index).invocationId())) {
                return index;
            }
        }
        return 0;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
