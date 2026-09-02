package com.hh.infrastructure.agent.agent.runtime.invocation;

import com.google.adk.events.Event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runner 调用前的 Event 边界。
 *
 * <p>非空 eventId 用于跨 MySQL 快照比较；没有 eventId 的 ADK Event 才使用数量作为兜底，避免
 * 失败补偿误处理调用开始前已经存在的历史 Event。</p>
 */
record InvocationBaseline(Set<String> eventIds, int eventCount) {

    /** 记录调用开始时已有 Event 的身份和数量，作为失败补偿的比较基线。 */
    static InvocationBaseline capture(List<Event> events) {
        Set<String> eventIds = new HashSet<>();
        events.stream()
                .map(Event::id)
                .filter(InvocationBaseline::hasText)
                .forEach(eventIds::add);
        return new InvocationBaseline(Set.copyOf(eventIds), events.size());
    }

    /** 返回相对于基线新增的 Event，优先按 ID 判断，缺失 ID 时才按位置兜底。 */
    List<Event> newEvents(List<Event> events) {
        List<Event> newEvents = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            Event event = events.get(index);
            boolean newEvent = hasText(event.id())
                    ? !eventIds.contains(event.id())
                    : index >= eventCount;
            if (newEvent) {
                newEvents.add(event);
            }
        }
        return List.copyOf(newEvents);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
