package com.hh.infrastructure.persistence;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.compiler.assembly.node.RunnerNode;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPlugin;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPluginFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 固化 pureSlidingWindow 的 Runner 装配边界：压缩计划留在 Runtime，不注册到 ADK App 自动执行。
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
    void pureSlidingWindowKeepsAutomaticCompactionDisabledOnApp() throws Exception {
        RunnerNode node = new RunnerNode();
        ReflectionTestUtils.setField(node, "applicationContext", mock(ApplicationContext.class));
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
        AgentAssemblyPipelineContext context = new AgentAssemblyPipelineContext();
        context.getAgentGroup().put("root-agent", mock(BaseAgent.class));

        Method createApp = RunnerNode.class.getDeclaredMethod(
                "createApp", AgentDefinition.class, AgentAssemblyPipelineContext.class);
        createApp.setAccessible(true);
        App app = (App) createApp.invoke(node, definition, context);

        assertThat(app.eventsCompactionConfig()).isNull();
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
