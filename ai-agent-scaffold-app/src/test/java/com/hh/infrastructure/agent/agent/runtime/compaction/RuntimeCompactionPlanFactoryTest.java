package com.hh.infrastructure.agent.agent.runtime.compaction;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.TailRetentionEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.FixedSizeCompactionStrategy;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.PureSlidingWindowCompactionStrategy;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.SlidingWindowCompactionStrategy;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.SlidingWindowCompactionCleanupPolicy;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证工厂只处理默认配置和策略路由，具体 ADK 装配由各策略单测覆盖。 */
class RuntimeCompactionPlanFactoryTest {

    private final CompactionCleanupPolicyRegistry cleanupPolicyRegistry = new CompactionCleanupPolicyRegistry(List.of(
            new SlidingWindowCompactionCleanupPolicy(),
            new FixedSizeCompactionCleanupPolicy(),
            new PureSlidingWindowCompactionCleanupPolicy()));
    private final RuntimeCompactionPlanFactory factory = new RuntimeCompactionPlanFactory(List.of(
            new FixedSizeCompactionStrategy(cleanupPolicyRegistry),
            new SlidingWindowCompactionStrategy(cleanupPolicyRegistry),
            new PureSlidingWindowCompactionStrategy(cleanupPolicyRegistry)));

    @Test
    void defaultsToFixedSizeStrategyWhenCompressionConfigurationIsMissing() {
        RuntimeCompactionPlan plan = factory.create(mock(BaseLlm.class), null);

        assertThat(plan.type()).isEqualTo(ContextCompressTypeEnum.FIXED_SIZE);
        assertThat(plan.compactor()).isInstanceOf(TailRetentionEventCompactor.class);
        assertThat(plan.tokenThreshold()).isEqualTo(12000);
        assertThat(plan.enabled()).isTrue();
    }

    @Test
    void routesEachConfiguredTypeToItsStrategy() {
        ContextCompressionDefinition config = new ContextCompressionDefinition();
        config.setType(ContextCompressTypeEnum.SLIDING_WINDOW.getType());
        assertThat(factory.create(mock(BaseLlm.class), config).type())
                .isEqualTo(ContextCompressTypeEnum.SLIDING_WINDOW);

        config.setType(ContextCompressTypeEnum.PURE_SLIDING_WINDOW.getType());
        assertThat(factory.create(null, config).type())
                .isEqualTo(ContextCompressTypeEnum.PURE_SLIDING_WINDOW);
    }

    @Test
    void fallsBackToFixedSizeStrategyForAnUnknownConfiguredType() {
        ContextCompressionDefinition config = new ContextCompressionDefinition();
        config.setType("unsupported");

        RuntimeCompactionPlan plan = factory.create(mock(BaseLlm.class), config);

        assertThat(plan.type()).isEqualTo(ContextCompressTypeEnum.FIXED_SIZE);
        assertThat(plan.compactor()).isInstanceOf(TailRetentionEventCompactor.class);
    }
}
