package com.hh.infrastructure.persistence;

import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.infrastructure.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.compiler.assembly.node.RunnerNode;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.impl.SlidingWindowCompactionCleanupPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 固化 pureSlidingWindow 的关键装配边界：保留 ADK 的窗口编排，但替换为无模型的 marker summarizer。
 */
class RunnerNodePureSlidingWindowTest {

    @Test
    void pureSlidingWindowBuildsAppWithAdkWindowConfigurationAndNoLlmSummarizer() throws Exception {
        RunnerNode node = new RunnerNode();
        ReflectionTestUtils.setField(node, "applicationContext", mock(ApplicationContext.class));
        ReflectionTestUtils.setField(node, "compactionCleanupPolicyRegistry", new CompactionCleanupPolicyRegistry(List.of(
                new SlidingWindowCompactionCleanupPolicy(),
                new FixedSizeCompactionCleanupPolicy(),
                new PureSlidingWindowCompactionCleanupPolicy())));

        ContextCompressionDefinition compression = new ContextCompressionDefinition();
        compression.setType("pureSlidingWindow");
        compression.setInvocationRetentionSize(3);
        RunnerDefinition runner = new RunnerDefinition();
        runner.setAgentName("root-agent");
        runner.setPluginNameList(List.of());
        runner.setContextCompress(compression);

        AgentDefinition definition = AgentDefinition.builder()
                .appName("pure_window_app")
                .runner(runner)
                .build();
        DynamicContext context = new DynamicContext();
        context.getAgentGroup().put("root-agent", mock(BaseAgent.class));

        Method createApp = RunnerNode.class.getDeclaredMethod(
                "createApp", AgentDefinition.class, DynamicContext.class);
        createApp.setAccessible(true);
        App app = (App) createApp.invoke(node, definition, context);

        assertThat(app.eventsCompactionConfig()).isNotNull();
        assertThat(app.eventsCompactionConfig().compactionInterval()).isEqualTo(1);
        assertThat(app.eventsCompactionConfig().overlapSize()).isEqualTo(3);
        assertThat(app.eventsCompactionConfig().summarizer())
                .isInstanceOf(com.hh.infrastructure.agent.session.compaction.summarizer.TrackingLlmEventSummarizer.class);
        assertThat(ReflectionTestUtils.getField(app.eventsCompactionConfig().summarizer(), "delegate"))
                .isInstanceOf(com.hh.infrastructure.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer.class);
    }
}
