package com.hh.observability;

import com.google.adk.runner.Runner;
import com.hh.config.agent.properties.AgentCatalogProperties;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntimeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Agent readiness 的离线测试，不创建模型、SessionService 或外部工具。
 */
class AgentRuntimeHealthIndicatorTest {

    @Test
    void disabledCatalogWithEmptyRegistryIsReady() {
        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setEnabled(false);
        AgentRuntimeHealthIndicator indicator = new AgentRuntimeHealthIndicator(
                properties, new AdkAgentRuntimeRegistry());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("enabled", false)
                .containsEntry("runtimeCount", 0);
    }

    @Test
    void enabledCatalogWithEmptyRegistryIsNotReady() {
        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setEnabled(true);
        AgentRuntimeHealthIndicator indicator = new AgentRuntimeHealthIndicator(
                properties, new AdkAgentRuntimeRegistry());

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("enabled", true)
                .containsEntry("runtimeCount", 0);
    }

    @Test
    void publishedRuntimeMakesEnabledCatalogReadyWithoutExposingAgentDetails() {
        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setEnabled(true);
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "desc", mock(Runner.class))));
        AgentRuntimeHealthIndicator indicator = new AgentRuntimeHealthIndicator(properties, registry);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("enabled", true)
                .containsEntry("runtimeCount", 1)
                .doesNotContainKeys("agentId", "agentIds", "configuration");
    }
}
