package com.hh.infrastructure.agent.session.compaction.cleanup.policy.impl;

import com.google.adk.events.Event;
import com.hh.infrastructure.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.session.compaction.cleanup.CompactionCleanupPlan;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.CompactionCleanupPolicy;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed-size 的删除策略。
 *
 * <p>Tail retention 只把需要重新摘要的旧摘要和较早 Event 传入 summarizer；最新保留窗口不会出现在
 * 入参中。因此本轮输入（包括旧 compaction 摘要）已被新摘要覆盖，可以在新摘要持久化后删除。</p>
 */
@Component
public class FixedSizeCompactionCleanupPolicy implements CompactionCleanupPolicy {

    @Override
    public String type() {
        return ContextCompressTypeEnum.FIXED_SIZE.getType();
    }

    @Override
    public CompactionCleanupPlan plan(List<Event> eventsToSummarize, CompactionCleanupContext context) {
        return new CompactionCleanupPlan(eventsToSummarize.stream()
                .map(Event::id)
                .filter(eventId -> eventId != null && !eventId.isBlank())
                .distinct()
                .toList());
    }
}
