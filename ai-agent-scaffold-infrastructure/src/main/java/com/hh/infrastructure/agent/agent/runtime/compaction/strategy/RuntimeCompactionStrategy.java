package com.hh.infrastructure.agent.agent.runtime.compaction.strategy;

import com.google.adk.models.BaseLlm;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.types.enums.ContextCompressTypeEnum;

/**
 * 一种已校验上下文压缩配置的 Runtime 装配策略。
 *
 * <p>策略仅决定 ADK compactor、摘要器和清理 metadata 如何组合；何时执行压缩、如何持久化摘要以及
 * 如何在同一事务删除源 Event，仍分别由 {@code SessionInvocationCoordinator} 与 Session Store 负责。</p>
 */
public interface RuntimeCompactionStrategy {

    /** 返回该策略对应的稳定 YAML 类型。 */
    ContextCompressTypeEnum type();

    /** 将配置及可选摘要模型装配成 Runtime 维护计划。 */
    RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition definition);
}
