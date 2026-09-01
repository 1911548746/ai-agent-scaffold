package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.TailRetentionEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.FixedSizeLlmEventSummarizer;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 固定大小压缩计划的装配策略。
 *
 * <p>协调器在本轮用户消息写入 Session 前，根据 tokenThreshold 判断是否调用该计划。compactor 的
 * tokenThreshold 固定为零，因此一旦协调器选中压缩，就立即摘要既有历史并保留最新 Event 窗口。</p>
 */
@Component
public class FixedSizeCompactionStrategy implements RuntimeCompactionStrategy {

    private final CompactionCleanupPolicyRegistry cleanupPolicyRegistry;

    public FixedSizeCompactionStrategy(CompactionCleanupPolicyRegistry cleanupPolicyRegistry) {
        this.cleanupPolicyRegistry = cleanupPolicyRegistry;
    }

    @Override
    public ContextCompressTypeEnum type() {
        return ContextCompressTypeEnum.FIXED_SIZE;
    }

    @Override
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition) {
        // fixedSize 需要模型将被淘汰的历史归纳为新摘要，缺少模型属于运行时装配错误。
        Objects.requireNonNull(llmModel, "llmModel must not be null for fixed-size context compaction");

        // 清理 metadata 使用实际保留参数，供 Session Store 在摘要成功落库后精确删除被覆盖 Event。
        FixedSizeLlmEventSummarizer summarizer = new FixedSizeLlmEventSummarizer(
                llmModel,
                cleanupPolicyRegistry.resolve(type().getType()),
                new CompactionCleanupContext(definition.getOverlapSize(), definition.getEventRetentionSize()));

        return new RuntimeCompactionPlan(
                type(),
                new TailRetentionEventCompactor(summarizer, definition.getEventRetentionSize(), 0),
                definition.getTokenThreshold(),
                true);
    }
}
