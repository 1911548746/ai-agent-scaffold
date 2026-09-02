package com.hh.infrastructure.agent.agent.runtime.compaction;

import com.google.adk.models.BaseLlm;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.google.adk.summarizer.TailRetentionEventCompactor;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.FixedSizeCompactionStrategy;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.PureSlidingWindowCompactionStrategy;
import com.hh.infrastructure.agent.agent.runtime.compaction.strategy.SlidingWindowCompactionStrategy;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.policy.impl.SlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.FixedSizeLlmEventSummarizer;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.infrastructure.agent.agent.runtime.compaction.summarizer.SlidingWindowLlmEventSummarizer;
import com.hh.types.enums.ContextCompressTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 验证各策略对 ADK compactor、摘要器和 Event 清理 metadata 的具体装配。 */
class RuntimeCompactionStrategyTest {

    @Test
    void fixedSizeUsesTailRetentionAndLlmSummaryWithRetentionCleanup() {
        RuntimeCompactionPlan plan = new FixedSizeCompactionStrategy(new FixedSizeCompactionCleanupPolicy())
                .create(mock(BaseLlm.class), new ContextCompressionDefinition());

        assertThat(plan.type()).isEqualTo(ContextCompressTypeEnum.FIXED_SIZE);
        assertThat(plan.compactor()).isInstanceOf(TailRetentionEventCompactor.class);
        assertThat(plan.tokenThreshold()).isEqualTo(12000);
        assertThat(ReflectionTestUtils.getField(plan.compactor(), "retentionSize")).isEqualTo(30);
        assertThat(ReflectionTestUtils.getField(plan.compactor(), "tokenThreshold")).isEqualTo(0);

        FixedSizeLlmEventSummarizer summarizer = fixedSizeSummarizer(
                ReflectionTestUtils.getField(plan.compactor(), "summarizer"));
        assertThat(ReflectionTestUtils.getField(summarizer, "delegate"))
                .isInstanceOf(LlmEventSummarizer.class);
        assertThat(ReflectionTestUtils.getField(summarizer, "cleanupPolicy"))
                .isInstanceOf(FixedSizeCompactionCleanupPolicy.class);
        assertThat(ReflectionTestUtils.getField(summarizer, "cleanupContext"))
                .isEqualTo(new CompactionCleanupContext(2, 30));
    }

    @Test
    void slidingWindowUsesConfiguredIntervalOverlapAndLlmCleanupPolicy() {
        ContextCompressionDefinition config = new ContextCompressionDefinition();
        config.setCompactionInterval(5);
        config.setOverlapSize(2);

        RuntimeCompactionPlan plan = new SlidingWindowCompactionStrategy(new SlidingWindowCompactionCleanupPolicy())
                .create(mock(BaseLlm.class), config);

        assertThat(plan.type()).isEqualTo(ContextCompressTypeEnum.SLIDING_WINDOW);
        assertThat(plan.compactor()).isInstanceOf(SlidingWindowEventCompactor.class);
        assertThat(plan.postResponseRequired()).isTrue();

        EventsCompactionConfig adkConfig = slidingWindowConfig(plan);
        assertThat(adkConfig.compactionInterval()).isEqualTo(5);
        assertThat(adkConfig.overlapSize()).isEqualTo(2);
        SlidingWindowLlmEventSummarizer summarizer = slidingWindowSummarizer(adkConfig.summarizer());
        assertThat(ReflectionTestUtils.getField(summarizer, "delegate"))
                .isInstanceOf(LlmEventSummarizer.class);
        assertThat(ReflectionTestUtils.getField(summarizer, "cleanupPolicy"))
                .isInstanceOf(SlidingWindowCompactionCleanupPolicy.class);
        assertThat(ReflectionTestUtils.getField(summarizer, "cleanupContext"))
                .isEqualTo(new CompactionCleanupContext(2, 30));
    }

    @Test
    void pureSlidingWindowUsesMarkerSummaryWithoutRequiringLlmModel() {
        ContextCompressionDefinition config = new ContextCompressionDefinition();
        config.setInvocationRetentionSize(3);

        RuntimeCompactionPlan plan = new PureSlidingWindowCompactionStrategy(new PureSlidingWindowCompactionCleanupPolicy())
                .create(null, config);

        assertThat(plan.type()).isEqualTo(ContextCompressTypeEnum.PURE_SLIDING_WINDOW);
        assertThat(plan.compactor()).isInstanceOf(SlidingWindowEventCompactor.class);
        assertThat(plan.postResponseRequired()).isTrue();

        EventsCompactionConfig adkConfig = slidingWindowConfig(plan);
        assertThat(adkConfig.compactionInterval()).isEqualTo(1);
        assertThat(adkConfig.overlapSize()).isEqualTo(3);
        assertThat(adkConfig.summarizer()).isInstanceOf(PureSlidingWindowEventSummarizer.class);
        PureSlidingWindowEventSummarizer summarizer = (PureSlidingWindowEventSummarizer) adkConfig.summarizer();
        assertThat(ReflectionTestUtils.getField(summarizer, "cleanupPolicy"))
                .isInstanceOf(PureSlidingWindowCompactionCleanupPolicy.class);
        assertThat(ReflectionTestUtils.getField(summarizer, "cleanupContext"))
                .isEqualTo(new CompactionCleanupContext(3, null));
    }

    private EventsCompactionConfig slidingWindowConfig(RuntimeCompactionPlan plan) {
        return (EventsCompactionConfig) ReflectionTestUtils.getField(plan.compactor(), "config");
    }

    private FixedSizeLlmEventSummarizer fixedSizeSummarizer(Object summarizer) {
        assertThat(summarizer).isInstanceOf(FixedSizeLlmEventSummarizer.class);
        return (FixedSizeLlmEventSummarizer) summarizer;
    }

    private SlidingWindowLlmEventSummarizer slidingWindowSummarizer(Object summarizer) {
        assertThat(summarizer).isInstanceOf(SlidingWindowLlmEventSummarizer.class);
        return (SlidingWindowLlmEventSummarizer) summarizer;
    }
}
