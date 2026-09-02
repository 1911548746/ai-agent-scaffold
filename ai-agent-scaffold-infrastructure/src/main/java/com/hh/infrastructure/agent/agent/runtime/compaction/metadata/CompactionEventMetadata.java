package com.hh.infrastructure.agent.agent.runtime.compaction.metadata;

import com.google.adk.events.Event;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.StringList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compaction Event 与 Session Store 之间使用的内部 metadata 协议。
 *
 * <p>摘要器只通过本类声明“摘要成功后可以删除哪些源 Event”；Session Store 在摘要 Event
 * 成功持久化的同一事务中读取这些 ID。协议集中在独立类型中，避免存储层依赖某个摘要器实现类。</p>
 */
public final class CompactionEventMetadata {

    /** 精确记录本次摘要覆盖范围，禁止由存储层按时间或行号推断删除范围。 */
    public static final String DELETABLE_EVENT_IDS_KEY = "scaffold.deletable-event-ids";

    /** 标记 pure sliding window 的空 compaction marker，可在源 Event 删除后省略持久化。 */
    public static final String PURE_SLIDING_WINDOW_MARKER_KEY = "scaffold.pure-sliding-window-marker";

    private CompactionEventMetadata() {
    }

    /** 将精确删除范围写入摘要 Event，重复执行时覆盖旧值而不是追加重复 metadata。 */
    public static Event attachDeletableEventIds(Event summaryEvent, List<String> eventIds) {
        List<CustomMetadata> metadata = new ArrayList<>(summaryEvent.customMetadata().orElse(List.of()));
        metadata.removeIf(item -> DELETABLE_EVENT_IDS_KEY.equals(item.key().orElse(null)));
        metadata.add(CustomMetadata.builder()
                .key(DELETABLE_EVENT_IDS_KEY)
                .stringListValue(StringList.builder().values(eventIds).build())
                .build());
        summaryEvent.setCustomMetadata(metadata);
        return summaryEvent;
    }

    /** 从 compaction Event 读取精确删除范围；格式异常或缺失时保守返回空集合。 */
    public static List<String> deletableEventIds(Event event) {
        if (event.actions() == null || event.actions().compaction().isEmpty()) {
            return List.of();
        }
        return event.customMetadata().orElse(List.of()).stream()
                .filter(metadata -> DELETABLE_EVENT_IDS_KEY.equals(metadata.key().orElse(null)))
                .flatMap(metadata -> metadata.stringListValue().stream())
                .flatMap(eventIds -> eventIds.values().stream())
                .flatMap(List::stream)
                .filter(eventId -> eventId != null && !eventId.isBlank())
                // 即使 metadata 被错误构造，也不能删除刚刚写入的摘要 Event。
                .filter(eventId -> !Objects.equals(event.id(), eventId))
                .distinct()
                .toList();
    }

    /** 判断 Event 是否为不包含模型摘要内容的 pure sliding window marker。 */
    public static boolean isPureSlidingWindowMarker(Event event) {
        return event.customMetadata().orElse(List.of()).stream()
                .anyMatch(metadata -> PURE_SLIDING_WINDOW_MARKER_KEY
                        .equals(metadata.key().orElse(null)));
    }
}
