package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.TailRetentionEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.FixedSizeLlmEventSummarizer;
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

    private final FixedSizeCompactionCleanupPolicy cleanupPolicy;

    /** 注入 fixedSize 专用清理策略，避免摘要器自行决定数据库删除范围。 */
    public FixedSizeCompactionStrategy(FixedSizeCompactionCleanupPolicy cleanupPolicy) {
        this.cleanupPolicy = cleanupPolicy;
    }

    /** fixedSize 对应配置中的稳定类型。 */
    @Override
    public ContextCompressTypeEnum type() {
        return ContextCompressTypeEnum.FIXED_SIZE;
    }

    /** 创建“调用前触发、TailRetention 保留尾部”的固定大小压缩计划。 */
    @Override
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition) {
        // fixedSize 需要模型将被淘汰的历史归纳为新摘要，缺少模型属于运行时装配错误。
        Objects.requireNonNull(llmModel, "llmModel must not be null for fixed-size context compaction");

        // 摘要器只生成删除 metadata；真正的删除要等摘要 Event 成功持久化后由 Session Store 完成。
        FixedSizeLlmEventSummarizer summarizer = new FixedSizeLlmEventSummarizer(
                llmModel,
                cleanupPolicy,
                new CompactionCleanupContext(definition.getOverlapSize(), definition.getEventRetentionSize()));

        return new RuntimeCompactionPlan(
                type(),
                new TailRetentionEventCompactor(summarizer, definition.getEventRetentionSize(), 0),
                definition.getTokenThreshold());
    }
}
