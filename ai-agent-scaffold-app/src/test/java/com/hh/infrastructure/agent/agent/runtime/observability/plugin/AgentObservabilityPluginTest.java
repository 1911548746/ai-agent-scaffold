package com.hh.infrastructure.agent.agent.runtime.observability.plugin;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADK 系统 Plugin 的离线钩子测试；所有 SDK 上下文均为 Mock，不会执行真实 Agent。
 */
class AgentObservabilityPluginTest {

    private SimpleMeterRegistry meterRegistry;
    private AgentObservabilityPlugin plugin;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        plugin = new AgentObservabilityPlugin("agent-a", meterRegistry);
    }

    @Test
    void recordsAgentModelToolAndTokenMetrics() {
        CallbackContext agentContext = callbackContext("invocation-a", "agent-event");
        BaseAgent agent = mock(BaseAgent.class);
        when(agent.name()).thenReturn("worker-agent");
        plugin.beforeAgentCallback(agent, agentContext).test().assertComplete().assertNoValues();
        plugin.afterAgentCallback(agent, agentContext).test().assertComplete().assertNoValues();

        CallbackContext modelContext = callbackContext("invocation-a", "model-event");
        LlmRequest.Builder requestBuilder = modelRequest("model-a");
        LlmResponse partialResponse = mock(LlmResponse.class);
        when(partialResponse.partial()).thenReturn(Optional.of(true));
        when(partialResponse.turnComplete()).thenReturn(Optional.of(false));
        when(partialResponse.usageMetadata()).thenReturn(Optional.empty());
        LlmResponse finalResponse = mock(LlmResponse.class);
        when(finalResponse.partial()).thenReturn(Optional.of(false));
        when(finalResponse.turnComplete()).thenReturn(Optional.of(true));
        when(finalResponse.usageMetadata()).thenReturn(Optional.of(
                GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(12)
                        .candidatesTokenCount(8)
                        .thoughtsTokenCount(3)
                        .cachedContentTokenCount(4)
                        .toolUsePromptTokenCount(5)
                        .totalTokenCount(20)
                        .build()));
        plugin.beforeModelCallback(modelContext, requestBuilder).test().assertComplete().assertNoValues();
        plugin.afterModelCallback(modelContext, partialResponse).test().assertComplete().assertNoValues();
        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TIMER_NAME).timer()).isNull();
        plugin.afterModelCallback(modelContext, finalResponse).test().assertComplete().assertNoValues();

        ToolContext toolContext = toolContext("invocation-a", "tool-event", "function-call-a");
        BaseTool tool = mock(BaseTool.class);
        when(tool.name()).thenReturn("search-tool");
        plugin.beforeToolCallback(tool, Map.of("query", "sensitive input"), toolContext)
                .test().assertComplete().assertNoValues();
        plugin.afterToolCallback(
                        tool,
                        Map.of("query", "sensitive input"),
                        toolContext,
                        Map.of("result", "sensitive output"))
                .test().assertComplete().assertNoValues();

        assertThat(meterRegistry.find(AgentObservabilityPlugin.AGENT_TIMER_NAME)
                .tags("agent.id", "agent-a", "adk.agent", "worker-agent", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TIMER_NAME)
                .tags("agent.id", "agent-a", "model.name", "model-a", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentObservabilityPlugin.TOOL_TIMER_NAME)
                .tags("agent.id", "agent-a", "tool.name", "search-tool", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TOKEN_COUNTER_NAME)
                .tags("agent.id", "agent-a", "model.name", "model-a", "kind", "total")
                .counter().count()).isEqualTo(20);
        assertTokenCount("thought", 3);
        assertTokenCount("cache", 4);
        assertTokenCount("tool", 5);
    }

    @Test
    void ignoresMissingOrNonPositiveTokenDetails() {
        CallbackContext modelContext = callbackContext("invocation-a", "model-event");
        plugin.beforeModelCallback(modelContext, modelRequest("model-a")).test().assertComplete();

        LlmResponse finalResponse = mock(LlmResponse.class);
        when(finalResponse.partial()).thenReturn(Optional.of(false));
        when(finalResponse.turnComplete()).thenReturn(Optional.of(true));
        when(finalResponse.usageMetadata()).thenReturn(Optional.of(
                GenerateContentResponseUsageMetadata.builder()
                        .thoughtsTokenCount(0)
                        .cachedContentTokenCount(0)
                        .toolUsePromptTokenCount(0)
                        .build()));

        plugin.afterModelCallback(modelContext, finalResponse).test().assertComplete();

        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TOKEN_COUNTER_NAME)
                .tag("kind", "thought").counter()).isNull();
        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TOKEN_COUNTER_NAME)
                .tag("kind", "cache").counter()).isNull();
        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TOKEN_COUNTER_NAME)
                .tag("kind", "tool").counter()).isNull();
    }

    @Test
    void errorHooksUseStableOutcomeWithoutRecordingPayloadOrExceptionMessage() {
        CallbackContext modelContext = callbackContext("invocation-a", "model-event");
        LlmRequest.Builder requestBuilder = modelRequest("model-a");
        plugin.beforeModelCallback(modelContext, requestBuilder).test().assertComplete();
        plugin.onModelErrorCallback(
                        modelContext,
                        requestBuilder,
                        new IllegalStateException("secret provider response"))
                .test().assertComplete().assertNoValues();

        ToolContext toolContext = toolContext("invocation-a", "tool-event", "function-call-a");
        BaseTool tool = mock(BaseTool.class);
        when(tool.name()).thenReturn("search-tool");
        plugin.beforeToolCallback(tool, Map.of("apiKey", "secret"), toolContext).test().assertComplete();
        plugin.onToolErrorCallback(
                        tool,
                        Map.of("apiKey", "secret"),
                        toolContext,
                        new IllegalArgumentException("secret tool response"))
                .test().assertComplete().assertNoValues();

        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TIMER_NAME)
                .tags("agent.id", "agent-a", "model.name", "model-a", "outcome", "error")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentObservabilityPlugin.TOOL_TIMER_NAME)
                .tags("agent.id", "agent-a", "tool.name", "search-tool", "outcome", "error")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.getMeters())
                .extracting(Meter::getId)
                .flatExtracting(Meter.Id::getTags)
                .allSatisfy(tag -> {
                    assertThat(tag.getKey()).doesNotContain("user", "session", "prompt", "message", "apiKey");
                    assertThat(tag.getValue()).doesNotContain("secret");
                });
    }

    private CallbackContext callbackContext(String invocationId, String eventId) {
        CallbackContext context = mock(CallbackContext.class);
        when(context.invocationId()).thenReturn(invocationId);
        when(context.eventId()).thenReturn(eventId);
        return context;
    }

    private ToolContext toolContext(String invocationId, String eventId, String functionCallId) {
        ToolContext context = mock(ToolContext.class);
        when(context.invocationId()).thenReturn(invocationId);
        when(context.eventId()).thenReturn(eventId);
        when(context.functionCallId()).thenReturn(Optional.of(functionCallId));
        return context;
    }

    private LlmRequest.Builder modelRequest(String modelName) {
        LlmRequest.Builder builder = mock(LlmRequest.Builder.class);
        LlmRequest request = mock(LlmRequest.class);
        when(builder.build()).thenReturn(request);
        when(request.model()).thenReturn(Optional.of(modelName));
        return builder;
    }

    private void assertTokenCount(String kind, double expected) {
        assertThat(meterRegistry.find(AgentObservabilityPlugin.MODEL_TOKEN_COUNTER_NAME)
                .tags("agent.id", "agent-a", "model.name", "model-a", "kind", kind)
                .counter().count()).isEqualTo(expected);
    }
}
