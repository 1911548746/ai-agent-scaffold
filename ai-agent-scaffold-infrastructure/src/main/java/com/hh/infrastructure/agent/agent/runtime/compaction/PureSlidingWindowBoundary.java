package com.hh.infrastructure.agent.agent.runtime.compaction;

import com.google.adk.events.Event;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * pureSlidingWindow 对一批 ADK Event 的统一窗口边界。
 *
 * <p>摘要器需要边界生成 marker，清理策略需要同一边界生成待删除 ID。集中计算可以保证两者不会因
 * invocation 分组逻辑稍有差异而产生“marker 忽略的范围”和“数据库删除的范围”不一致。</p>
 */
public record PureSlidingWindowBoundary(int retainedStart, List<String> expiredEventIds) {

    public PureSlidingWindowBoundary {
        if (retainedStart < 0) {
            throw new IllegalArgumentException("retainedStart must not be negative");
        }
        expiredEventIds = List.copyOf(expiredEventIds);
    }

    /** 根据 Event 顺序和需要保留的完整 invocation 数量计算过期前缀与保留起点。 */
    public static PureSlidingWindowBoundary resolve(List<Event> events, Integer retentionSize) {
        int resolvedRetentionSize = retentionSize == null ? 0 : Math.max(0, retentionSize);
        if (resolvedRetentionSize < 1 || events.isEmpty()) {
            return new PureSlidingWindowBoundary(0, List.of());
        }

        // 以首次出现顺序记录 invocation，保证窗口边界与 ADK 的 Event 顺序一致。
        Set<String> invocationIds = new LinkedHashSet<>();
        for (Event event : events) {
            if (hasText(event.invocationId())) {
                invocationIds.add(event.invocationId());
            }
        }
        if (invocationIds.size() <= resolvedRetentionSize) {
            return new PureSlidingWindowBoundary(0, List.of());
        }

        List<String> orderedInvocationIds = new ArrayList<>(invocationIds);
        String firstRetainedInvocationId = orderedInvocationIds.get(
                orderedInvocationIds.size() - resolvedRetentionSize);
        int retainedStart = 0;
        for (int index = 0; index < events.size(); index++) {
            if (firstRetainedInvocationId.equals(events.get(index).invocationId())) {
                retainedStart = index;
                break;
            }
        }
        if (retainedStart == 0) {
            return new PureSlidingWindowBoundary(0, List.of());
        }

        List<String> expiredEventIds = events.subList(0, retainedStart).stream()
                .map(Event::id)
                .filter(PureSlidingWindowBoundary::hasText)
                .distinct()
                .toList();
        return new PureSlidingWindowBoundary(retainedStart, expiredEventIds);
    }

    public boolean hasExpiredEvents() {
        // retainedStart 大于零表示当前批次确实存在已过期的 Event 前缀。
        return retainedStart > 0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
