package com.hh.infrastructure.agent.agent.runtime.compaction;

import com.google.adk.models.BaseLlm;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.RuntimeCompactionStrategy;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 将已校验的上下文压缩配置装配为 Runtime 执行计划。
 *
 * <p>工厂只处理配置默认值与策略路由。ADK compactor、摘要器及持久化 Event 清理 metadata 的具体
 * 绑定由对应策略完成，RunnerNode 只需获取最终计划。</p>
 */
@Component
public class RuntimeCompactionPlanFactory {

    private final Map<ContextCompressTypeEnum, RuntimeCompactionStrategy> strategies;

    /**
     * 收集并校验所有内置压缩策略，启动时即拒绝重复或缺失的策略实现。
     */
    public RuntimeCompactionPlanFactory(List<RuntimeCompactionStrategy> strategies) {
        Map<ContextCompressTypeEnum, RuntimeCompactionStrategy> resolved = new EnumMap<>(ContextCompressTypeEnum.class);
        // 用枚举作为内部键，避免运行时依赖 YAML 字符串进行二次分支判断。
        for (RuntimeCompactionStrategy strategy : strategies) {
            RuntimeCompactionStrategy previous = resolved.putIfAbsent(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException("duplicate runtime compaction strategy: " + strategy.type());
            }
        }
        // 每一种配置类型都必须有唯一实现，否则不能保证 Runtime 的压缩语义稳定。
        for (ContextCompressTypeEnum type : ContextCompressTypeEnum.values()) {
            if (!resolved.containsKey(type)) {
                throw new IllegalStateException("missing runtime compaction strategy: " + type);
            }
        }
        this.strategies = Map.copyOf(resolved);
    }

    /**
     * 根据上下文压缩定义创建 Runtime 维护计划。
     *
     * <p>配置缺失或未知类型均维持既有的 fixedSize 回退语义；pureSlidingWindow 不读取 LLM，
     * 因此可以在没有摘要模型的场景下工作。</p>
     */
    public RuntimeCompactionPlan create(BaseLlm llmModel, ContextCompressionDefinition configured) {
        // Loader 通常会提供非空配置，但这里保留默认值处理，保证独立装配和测试调用也有确定行为。
        ContextCompressionDefinition config = configured == null
                ? new ContextCompressionDefinition()
                : configured;
        ContextCompressTypeEnum type = ContextCompressTypeEnum.getByType(config.getType());

        // 枚举解析负责未知类型回退为 fixedSize；工厂只按稳定枚举值选择具体策略。
        return strategies.get(type).create(llmModel, config);
    }
}
