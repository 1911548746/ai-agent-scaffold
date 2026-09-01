package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

/**
 * 不调用模型的纯滑动窗口压缩计划装配策略。
 *
 * <p>ADK compactor 按完整 invocation 选择保留窗口；本策略写入 compaction marker，而不是 LLM 摘要。
 * marker 携带精确的可删除 Event ID，Session Store 在 marker 持久化成功后才物理删除窗口前缀。</p>
 */
@Component
public class PureSlidingWindowCompactionStrategy implements RuntimeCompactionStrategy {

    private final CompactionCleanupPolicyRegistry cleanupPolicyRegistry;

    public PureSlidingWindowCompactionStrategy(CompactionCleanupPolicyRegistry cleanupPolicyRegistry) {
        this.cleanupPolicyRegistry = cleanupPolicyRegistry;
    }

    @Override
    public ContextCompressTypeEnum type() {
        return ContextCompressTypeEnum.PURE_SLIDING_WINDOW;
    }

    @Override
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition) {
        // pureSlidingWindow 不读取 llmModel，因此可用于没有摘要模型的本地历史裁剪场景。
        int retentionSize = definition.getInvocationRetentionSize();
        PureSlidingWindowEventSummarizer summarizer = new PureSlidingWindowEventSummarizer(
                retentionSize,
                cleanupPolicyRegistry.resolve(type().getType()),
                new CompactionCleanupContext(retentionSize, null));
        EventsCompactionConfig adkConfig = EventsCompactionConfig.builder()
                // 每轮回复后均检查完整 invocation 窗口，实际是否产生 marker 由 summarizer 决定。
                .compactionInterval(1)
                .overlapSize(retentionSize)
                .summarizer(summarizer)
                .build();

        return new RuntimeCompactionPlan(type(), new SlidingWindowEventCompactor(adkConfig), 0, true);
    }
}
