package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.SlidingWindowLlmEventSummarizer;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 生成 LLM 摘要的滑动窗口压缩计划装配策略。
 *
 * <p>主回复成功后，协调器异步调用该 compactor。摘要器保留 overlap invocation 的原始 Event，并仅在
 * 摘要 Event 成功写入后由 Session Store 删除窗口前缀，避免 overlap 被错误物理删除。</p>
 */
@Component
public class SlidingWindowCompactionStrategy implements RuntimeCompactionStrategy {

    private final CompactionCleanupPolicyRegistry cleanupPolicyRegistry;

    public SlidingWindowCompactionStrategy(CompactionCleanupPolicyRegistry cleanupPolicyRegistry) {
        this.cleanupPolicyRegistry = cleanupPolicyRegistry;
    }

    @Override
    public ContextCompressTypeEnum type() {
        return ContextCompressTypeEnum.SLIDING_WINDOW;
    }

    @Override
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition) {
        // slidingWindow 的窗口前缀必须被模型概括为摘要，因此不能在没有 LLM 的场景装配。
        Objects.requireNonNull(llmModel, "llmModel must not be null for sliding-window context compaction");

        // 公共摘要模板记录本次摘要真正覆盖的 Event，清理策略再排除需要保留的 overlap invocation。
        SlidingWindowLlmEventSummarizer summarizer = new SlidingWindowLlmEventSummarizer(
                llmModel,
                cleanupPolicyRegistry.resolve(type().getType()),
                new CompactionCleanupContext(definition.getOverlapSize(), definition.getEventRetentionSize()));
        EventsCompactionConfig adkConfig = EventsCompactionConfig.builder()
                .compactionInterval(definition.getCompactionInterval())
                .overlapSize(definition.getOverlapSize())
                .summarizer(summarizer)
                .build();

        return new RuntimeCompactionPlan(type(), new SlidingWindowEventCompactor(adkConfig), 0, true);
    }
}
