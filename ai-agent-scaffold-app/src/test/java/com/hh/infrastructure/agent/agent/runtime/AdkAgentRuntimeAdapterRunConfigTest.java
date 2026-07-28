package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import io.reactivex.rxjava3.core.Flowable;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证编译后的 Agent 级 RunConfig 会被所有对话入口一致使用。
 */
@SuppressWarnings("deprecation")
class AdkAgentRuntimeAdapterRunConfigTest {

    @Test
    void appliesExecutionPolicyToSyncStreamingAndMultimodalCalls() {
        Runner runner = mock(Runner.class);
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.empty());

        RunConfig baseRunConfig = RunConfig.builder()
                .groupFunctionResponsesInHistoryOverride(Optional.of(true))
                .build();
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "test", runner, baseRunConfig)));
        AdkAgentRuntimeAdapter adapter = new AdkAgentRuntimeAdapter(registry);

        adapter.run("agent-a", "user-a", "session-a", "hello");
        adapter.runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(mock(ChatStreamObserver.class));
        adapter.run("agent-a", ChatCommandEntity.builder()
                .userId("user-a")
                .sessionId("session-a")
                .texts(List.of(new ChatCommandEntity.Content.Text("hello")))
                .build());

        ArgumentCaptor<RunConfig> configCaptor = ArgumentCaptor.forClass(RunConfig.class);
        verify(runner, times(3)).runAsync(
                eq("user-a"), eq("session-a"), any(Content.class), configCaptor.capture());

        assertThat(configCaptor.getAllValues())
                .extracting(RunConfig::groupFunctionResponsesInHistory)
                .containsExactly(true, true, true);
        assertThat(configCaptor.getAllValues())
                .extracting(RunConfig::streamingMode)
                .containsExactly(
                        RunConfig.StreamingMode.NONE,
                        RunConfig.StreamingMode.SSE,
                        RunConfig.StreamingMode.NONE);
    }

    @Test
    void mapsAsyncModelFailureForSyncAndStreamingEntrypoints() {
        Runner runner = mock(Runner.class);
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.error(new TimeoutException("request timed out")));
        AdkAgentRuntimeAdapter adapter = adapter(runner);

        assertThatThrownBy(() -> adapter.run("agent-a", "user-a", "session-a", "hello"))
                .isInstanceOfSatisfying(ModelInvocationException.class,
                        error -> assertThat(error.failureType())
                                .isEqualTo(ModelInvocationException.FailureType.TIMEOUT));

        AtomicReference<Throwable> streamError = new AtomicReference<>();
        adapter.runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(new ChatStreamObserver() {
                    @Override
                    public void onEvent(com.hh.application.agent.model.ChatEvent event) {
                    }

                    @Override
                    public void onError(Throwable error) {
                        streamError.set(error);
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        assertThat(streamError.get())
                .isInstanceOfSatisfying(ModelInvocationException.class,
                        error -> assertThat(error.failureType())
                                .isEqualTo(ModelInvocationException.FailureType.TIMEOUT));
    }

    private AdkAgentRuntimeAdapter adapter(Runner runner) {
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "test", runner, RunConfig.builder().build())));
        return new AdkAgentRuntimeAdapter(registry);
    }
}
