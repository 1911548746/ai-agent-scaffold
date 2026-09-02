package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.SlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.SlidingWindowLlmEventSummarizer;
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

    private final SlidingWindowCompactionCleanupPolicy cleanupPolicy;

    /** 注入 slidingWindow 专用清理策略，保证 overlap invocation 不被误删。 */
    public SlidingWindowCompactionStrategy(SlidingWindowCompactionCleanupPolicy cleanupPolicy) {
        this.cleanupPolicy = cleanupPolicy;
    }

    /** slidingWindow 对应配置中的稳定类型。 */
    @Override
    public ContextCompressTypeEnum type() {
        return ContextCompressTypeEnum.SLIDING_WINDOW;
    }

    /** 创建“主回复后触发、保留 overlap invocation”的 LLM 滑动窗口压缩计划。 */
    @Override
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition) {
        // slidingWindow 的窗口前缀必须被模型概括为摘要，因此不能在没有 LLM 的场景装配。
        Objects.requireNonNull(llmModel, "llmModel must not be null for sliding-window context compaction");

        // 摘要器记录本次覆盖的 Event；清理策略随后排除 overlap invocation，避免下一轮失去必要上下文。
        SlidingWindowLlmEventSummarizer summarizer = new SlidingWindowLlmEventSummarizer(
                llmModel,
                cleanupPolicy,
                new CompactionCleanupContext(definition.getOverlapSize(), definition.getEventRetentionSize()));
        EventsCompactionConfig adkConfig = EventsCompactionConfig.builder()
                .compactionInterval(definition.getCompactionInterval())
                .overlapSize(definition.getOverlapSize())
                .summarizer(summarizer)
                .build();

        return new RuntimeCompactionPlan(type(), new SlidingWindowEventCompactor(adkConfig), 0);
    }
}
