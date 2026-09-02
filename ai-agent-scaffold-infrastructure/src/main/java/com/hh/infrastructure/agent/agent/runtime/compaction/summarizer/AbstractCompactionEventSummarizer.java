package com.hh.infrastructure.agent.agent.runtime.compaction.summarizer;

import com.google.adk.events.Event;
import com.google.adk.summarizer.BaseEventSummarizer;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.CompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.metadata.CompactionEventMetadata;
import io.reactivex.rxjava3.core.Maybe;

import java.util.List;

/**
 * 压缩策略专属摘要器的公共模板。
 *
 * <p>子类只决定如何将本轮 Event 转换为摘要或 marker。公共流程根据 ADK 实际交给摘要器的 Event 计算可删除
 * 集合，并将精确 Event ID 写入输出 metadata；Session Store 在输出 Event 成功持久化后才据此删除源 Event。</p>
 */
public abstract class AbstractCompactionEventSummarizer implements BaseEventSummarizer {

    private final CompactionCleanupPolicy cleanupPolicy;
    private final CompactionCleanupContext cleanupContext;

    /** 保存策略专用的清理规则和本次装配得到的保留参数。 */
    protected AbstractCompactionEventSummarizer(
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        this.cleanupPolicy = cleanupPolicy;
        this.cleanupContext = cleanupContext;
    }

    /**
     * 处理 ADK 交给本次压缩的 Event，并把可删除范围附加到输出摘要的内部 metadata。
     *
     * <p>该方法刻意不直接删除 Event：摘要器运行在 ADK 压缩阶段，只有 Session Store 成功持久化
     * 输出摘要后，才能在同一事务中删除源 Event。</p>
     */
    @Override
    public final Maybe<Event> summarizeEvents(List<Event> events) {
        // 先依据“实际收到的窗口”计算删除范围，避免存储层重新按时间戳或行号猜测边界。
        CompactionCleanupPlan cleanupPlan = cleanupPolicy.plan(events, cleanupContext);

        // 由策略专属实现生成 LLM 摘要或纯窗口 marker；窗口未过期时也可以返回空结果。
        Maybe<Event> rawSummarizedEvents = summarizeCompactionEvents(events);
        // 只有存在输出 Event 时才附加 metadata；空结果不会触发任何清理。
        Maybe<Event> summarizedEvents = rawSummarizedEvents.map(
                summaryEvent -> attachDeletableEventIds(summaryEvent, cleanupPlan.deletableEventIds()));
        return summarizedEvents;
    }

    /** 生成摘要 Event；LLM 策略返回模型摘要，纯窗口策略返回控制 marker。 */
    protected abstract Maybe<Event> summarizeCompactionEvents(List<Event> events);

    /** 将待删除 Event ID 写入摘要 Event，交由 Session Store 在事务内消费。 */
    private Event attachDeletableEventIds(Event summaryEvent, List<String> deletableEventIds) {
        return CompactionEventMetadata.attachDeletableEventIds(summaryEvent, deletableEventIds);
    }
}
