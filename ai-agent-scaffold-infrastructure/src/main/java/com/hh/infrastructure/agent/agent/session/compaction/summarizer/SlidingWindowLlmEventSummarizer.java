package com.hh.infrastructure.agent.agent.session.compaction.summarizer;

import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicy;
import io.reactivex.rxjava3.core.Maybe;

import java.util.List;

/**
 * slidingWindow 压缩使用的 LLM 摘要器。
 *
 * <p>滑动窗口会携带 overlap invocation 参与下一轮摘要，因此其清理策略与 fixedSize 不同。独立类型让窗口
 * 摘要 Prompt 可单独演进，同时不会影响 fixedSize 的历史归纳语义。</p>
 */
public final class SlidingWindowLlmEventSummarizer extends AbstractCompactionEventSummarizer {

    private final LlmEventSummarizer delegate;

    public SlidingWindowLlmEventSummarizer(
            BaseLlm llmModel,
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        super(cleanupPolicy, cleanupContext);
        this.delegate = new LlmEventSummarizer(llmModel);
    }

    @Override
    protected Maybe<Event> summarizeCompactionEvents(List<Event> events) {
        return delegate.summarizeEvents(events);
    }
}
