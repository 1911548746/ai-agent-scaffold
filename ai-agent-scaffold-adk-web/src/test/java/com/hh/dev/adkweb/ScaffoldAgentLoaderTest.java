package com.hh.dev.adkweb;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.summarizer.EventCompactor;
import com.google.adk.runner.Runner;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.types.enums.ContextCompressTypeEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScaffoldAgentLoaderTest {

    @Test
    void shouldExposePublishedAgentsByAppName() {
        BaseAgent firstAgent = mock(BaseAgent.class);
        BaseAgent secondAgent = mock(BaseAgent.class);
        ScaffoldAgentLoader loader = new ScaffoldAgentLoader(List.of(
                runtime("hello-app", "agent-1", firstAgent),
                runtime("pipeline-app", "agent-2", secondAgent)));

        assertThat(loader.listAgents()).containsExactly("hello-app", "pipeline-app");
        assertThat(loader.loadAgent("hello-app")).isSameAs(firstAgent);
        assertThat(loader.loadAgent("pipeline-app")).isSameAs(secondAgent);
    }

    @Test
    void shouldRejectEmptyRuntimeSnapshot() {
        assertThatThrownBy(() -> new ScaffoldAgentLoader(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ADK Web cannot start: Agent Runtime Registry is empty");
    }

    @Test
    void shouldRejectDuplicateAppName() {
        assertThatThrownBy(() -> new ScaffoldAgentLoader(List.of(
                runtime("same-app", "agent-1", mock(BaseAgent.class)),
                runtime("same-app", "agent-2", mock(BaseAgent.class)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ADK Web cannot start: duplicate appName 'same-app'");
    }

    @Test
    void shouldRejectUnknownOrBlankAppName() {
        ScaffoldAgentLoader loader = new ScaffoldAgentLoader(List.of(
                runtime("hello-app", "agent-1", mock(BaseAgent.class))));

        assertThatThrownBy(() -> loader.loadAgent("missing-app"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Agent appName not found: missing-app");
        assertThatThrownBy(() -> loader.loadAgent(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Agent appName must not be blank");
    }

    @Test
    void shouldKeepStartupSnapshotWhenRegistryIsReplaced() {
        BaseAgent originalAgent = mock(BaseAgent.class);
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry(new SimpleMeterRegistry());
        registry.publish(List.of(runtime("original-app", "agent-1", originalAgent)));
        ScaffoldAgentLoader loader = new ScaffoldAgentLoader(registry.snapshot().values());

        registry.publish(List.of(runtime(
                "replacement-app", "agent-2", mock(BaseAgent.class))));

        assertThat(loader.listAgents()).containsExactly("original-app");
        assertThat(loader.loadAgent("original-app")).isSameAs(originalAgent);
        assertThatThrownBy(() -> loader.loadAgent("replacement-app"))
                .isInstanceOf(NoSuchElementException.class);
    }

    private AdkAgentRuntime runtime(String appName, String agentId, BaseAgent rootAgent) {
        Runner runner = mock(Runner.class);
        when(runner.agent()).thenReturn(rootAgent);
        return new AdkAgentRuntime(
                appName,
                agentId,
                appName + "-name",
                appName + "-description",
                runner,
                RunConfig.builder().build(),
                new RuntimeCompactionPlan(ContextCompressTypeEnum.FIXED_SIZE, mock(EventCompactor.class), Integer.MAX_VALUE));
    }
}
