package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.PureSlidingWindowEventSummarizer;
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

    private final PureSlidingWindowCompactionCleanupPolicy cleanupPolicy;

    /** 注入纯窗口清理策略，使 marker 边界与数据库删除边界保持一致。 */
    public PureSlidingWindowCompactionStrategy(PureSlidingWindowCompactionCleanupPolicy cleanupPolicy) {
        this.cleanupPolicy = cleanupPolicy;
    }

    /** pureSlidingWindow 对应配置中的稳定类型。 */
    @Override
    public ContextCompressTypeEnum type() {
        return ContextCompressTypeEnum.PURE_SLIDING_WINDOW;
    }

    /** 创建“主回复后检查、不调用模型”的纯滑动窗口压缩计划。 */
    @Override
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition) {
        // pureSlidingWindow 不读取 llmModel，因此可用于没有摘要模型的本地历史裁剪场景。
        int retentionSize = definition.getInvocationRetentionSize();
        PureSlidingWindowEventSummarizer summarizer = new PureSlidingWindowEventSummarizer(
                retentionSize,
                cleanupPolicy,
                new CompactionCleanupContext(retentionSize, null));
        EventsCompactionConfig adkConfig = EventsCompactionConfig.builder()
                // 每轮回复后检查完整 invocation 窗口，是否实际产生 marker 由摘要器决定。
                .compactionInterval(1)
                .overlapSize(retentionSize)
                .summarizer(summarizer)
                .build();

        return new RuntimeCompactionPlan(type(), new SlidingWindowEventCompactor(adkConfig), 0);
    }
}
