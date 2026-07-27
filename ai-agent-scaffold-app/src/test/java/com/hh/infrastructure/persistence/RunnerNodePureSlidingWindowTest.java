package com.hh.infrastructure.persistence;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.compiler.assembly.node.RunnerNode;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.PureSlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.impl.SlidingWindowCompactionCleanupPolicy;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPlugin;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPluginFactory;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
@SuppressWarnings("deprecation")
class RunnerNodePureSlidingWindowTest {

    @Test
    void runnerExecutionPolicyIsCompiledIntoRunConfig() throws Exception {
        RunnerNode node = new RunnerNode();
        RunnerDefinition runner = new RunnerDefinition();
        runner.setGroupFunctionResponsesInHistory(true);

        Method createRunConfig = RunnerNode.class.getDeclaredMethod(
                "createRunConfig", RunnerDefinition.class);
        createRunConfig.setAccessible(true);
        RunConfig runConfig = (RunConfig) createRunConfig.invoke(node, runner);
        RunConfig automaticRunConfig = (RunConfig) createRunConfig.invoke(node, new RunnerDefinition());

        assertThat(runConfig.groupFunctionResponsesInHistory()).isTrue();
        assertThat(runConfig.streamingMode()).isEqualTo(RunConfig.StreamingMode.NONE);
        assertThat(automaticRunConfig.groupFunctionResponsesInHistoryOverride()).isEmpty();
    }

    @Test
    void pureSlidingWindowBuildsAppWithAdkWindowConfigurationAndNoLlmSummarizer() throws Exception {
        RunnerNode node = new RunnerNode();
        ReflectionTestUtils.setField(node, "applicationContext", mock(ApplicationContext.class));
        ReflectionTestUtils.setField(node, "compactionCleanupPolicyRegistry", new CompactionCleanupPolicyRegistry(List.of(
                new SlidingWindowCompactionCleanupPolicy(),
                new FixedSizeCompactionCleanupPolicy(),
                new PureSlidingWindowCompactionCleanupPolicy())));
        ReflectionTestUtils.setField(node, "agentObservabilityPluginFactory",
                new AgentObservabilityPluginFactory(new SimpleMeterRegistry()));

        ContextCompressionDefinition compression = new ContextCompressionDefinition();
        compression.setType("pureSlidingWindow");
        compression.setInvocationRetentionSize(3);
        RunnerDefinition runner = new RunnerDefinition();
        runner.setAgentName("root-agent");
        runner.setPluginNameList(List.of());
        runner.setContextCompress(compression);

        AgentDefinition definition = AgentDefinition.builder()
                .id("pure-window-definition")
                .appName("pure_window_app")
                .agentDescriptor(agentDescriptor())
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
                .isInstanceOf(TrackingLlmEventSummarizer.class);
        assertThat(ReflectionTestUtils.getField(app.eventsCompactionConfig().summarizer(), "delegate"))
                .isInstanceOf(PureSlidingWindowEventSummarizer.class);
        assertThat(app.plugins())
                .singleElement()
                .isInstanceOf(AgentObservabilityPlugin.class);
    }

    private AgentDescriptor agentDescriptor() {
        AgentDescriptor descriptor = new AgentDescriptor();
        descriptor.setAgentId("pure-window-agent");
        descriptor.setAgentName("Pure Window Agent");
        descriptor.setAgentDesc("test");
        return descriptor;
    }
}
