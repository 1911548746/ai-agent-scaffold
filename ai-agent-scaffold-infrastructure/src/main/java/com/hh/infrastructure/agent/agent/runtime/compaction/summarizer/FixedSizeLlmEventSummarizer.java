package com.hh.infrastructure.agent.agent.runtime.compaction.summarizer;

import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.CompactionCleanupPolicy;
import io.reactivex.rxjava3.core.Maybe;

import java.util.List;

/**
 * fixedSize 压缩使用的 LLM 摘要器。
 *
 * <p>该类型拥有 fixedSize 的摘要语义和 ADK LLM 摘要器实例。后续如需保留关键信息、调整输出语言或采用专用
 * 摘要模型，只修改此类，不影响 slidingWindow 或纯窗口裁剪。</p>
 */
public final class FixedSizeLlmEventSummarizer extends AbstractCompactionEventSummarizer {

    private final LlmEventSummarizer delegate;

    /** 创建 fixedSize 专属的 ADK LLM 摘要器。 */
    public FixedSizeLlmEventSummarizer(
            BaseLlm llmModel,
            CompactionCleanupPolicy cleanupPolicy,
            CompactionCleanupContext cleanupContext) {
        super(cleanupPolicy, cleanupContext);
        this.delegate = new LlmEventSummarizer(llmModel);
    }

    /** 委托 ADK 生成固定大小压缩所需的历史摘要。 */
    @Override
    protected Maybe<Event> summarizeCompactionEvents(List<Event> events) {
        return delegate.summarizeEvents(events);
    }
}
