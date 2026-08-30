package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.ToolConfirmation;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventCompactor;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatSubscription;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.valobj.AttachmentReference;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.invocation.SessionInvocationCoordinator;
import com.hh.types.enums.ContextCompressTypeEnum;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
        Runner runner = runner(session());
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.empty());

        RunConfig baseRunConfig = RunConfig.builder()
                .groupFunctionResponsesInHistoryOverride(Optional.of(true))
                .build();
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "test", runner, baseRunConfig)));
        AdkAgentRuntimeAdapter adapter = new AdkAgentRuntimeAdapter(
                registry, new SessionInvocationCoordinator(Runnable::run));

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
    void sendsArtifactNameWithoutLoadingInlineDataIntoUserEvent() {
        Runner runner = runner(session());
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.empty());
        AttachmentReference reference = new AttachmentReference(
                "attachment-9f2a1742-0c98-4c18-a558-3ee79dfb77ad",
                "image.png",
                "image/png",
                3);

        AdkAgentRuntimeAdapter adapter = adapter(runner);
        adapter.run("agent-a", ChatCommandEntity.builder()
                .userId("user-a")
                .sessionId("session-a")
                .attachments(List.of(reference))
                .build());

        ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
        verify(runner).runAsync(eq("user-a"), eq("session-a"), contentCaptor.capture(), any(RunConfig.class));
        List<Part> parts = contentCaptor.getValue().parts().orElseThrow();
        assertThat(parts).allMatch(part -> part.inlineData().isEmpty());
        assertThat(parts.get(0).text().orElseThrow()).contains(reference.artifactName());
        assertThat(parts.get(0).text().orElseThrow()).contains("load_artifacts");
        assertThat(contentCaptor.getValue().toJson()).doesNotContain("attachments/").doesNotContain("http://");
    }

    @Test
    void resumesConfirmationWithAdkFunctionResponseOnSameSession() {
        Runner runner = runner(session());
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.empty());
        AdkAgentRuntimeAdapter adapter = adapter(runner);

        adapter.runConfirmation("agent-a", "user-a", "session-a", "confirmation-call", true);

        ArgumentCaptor<Content> contentCaptor = ArgumentCaptor.forClass(Content.class);
        verify(runner).runAsync(eq("user-a"), eq("session-a"), contentCaptor.capture(), any(RunConfig.class));
        FunctionResponse response = contentCaptor.getValue().parts().orElseThrow().get(0)
                .functionResponse().orElseThrow();
        assertThat(response.id()).contains("confirmation-call");
        assertThat(response.name()).contains("adk_request_confirmation");
        assertThat(response.response()).isPresent();
        assertThat(response.response().orElseThrow()).containsEntry("confirmed", true);
    }

    @Test
    void mapsAsyncModelFailureForSyncAndStreamingEntrypoints() {
        Runner runner = runner(session());
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

    @Test
    void syncFailureAndStreamCancellationDiscardInvocationEvents() {
        Session syncSession = session();
        Runner syncRunner = runner(syncSession);
        when(syncRunner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenAnswer(invocation -> {
                    syncSession.events().add(Event.builder()
                            .id("sync-user-a")
                            .invocationId("sync-failed")
                            .build());
                    return Flowable.error(new TimeoutException("request timed out"));
                });
        AdkAgentRuntimeAdapter syncAdapter = adapterWithCleanup(syncRunner);

        assertThatThrownBy(() -> syncAdapter.run("agent-a", "user-a", "session-a", "hello"))
                .isInstanceOf(ModelInvocationException.class);
        assertThat(syncSession.events()).isEmpty();

        Session streamSession = session();
        Runner streamRunner = runner(streamSession);
        when(streamRunner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.never());
        AdkAgentRuntimeAdapter streamAdapter = adapterWithCleanup(streamRunner);
        ChatSubscription subscription = streamAdapter.runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(mock(ChatStreamObserver.class));
        streamSession.events().add(Event.builder()
                .id("stream-user-a")
                .invocationId("stream-cancelled")
                .build());

        subscription.cancel();
        subscription.cancel();

        assertThat(streamSession.events()).isEmpty();
    }

    @Test
    void streamingForwardsPartialTextWithoutRepeatingCompletedContent() {
        Runner runner = runner(session());
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.just(
                        textEvent("partial-a", "你", true),
                        textEvent("partial-b", "好", true),
                        textEvent("completed-a", "你好", false)));
        AdkAgentRuntimeAdapter adapter = adapter(runner);
        List<String> outputs = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        adapter.runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(observer(outputs, completed));

        assertThat(outputs).containsExactly("你", "好");
        assertThat(completed).isTrue();
    }

    @Test
    void streamingStillForwardsTerminalOnlyResponseAndResetsAtToolBoundary() {
        Runner terminalRunner = runner(session());
        when(terminalRunner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.just(textEvent("completed-a", "完整回复", false)));
        List<String> terminalOutputs = new ArrayList<>();

        adapter(terminalRunner).runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(observer(terminalOutputs, new AtomicBoolean()));

        assertThat(terminalOutputs).containsExactly("完整回复");

        Runner toolRunner = runner(session());
        Event functionCall = Event.builder()
                .id("tool-a")
                .author("agent-a")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromFunctionCall("lookup", java.util.Map.of())))
                        .build())
                .build();
        when(toolRunner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.just(
                        textEvent("partial-a", "正在查询", true),
                        functionCall,
                        textEvent("completed-b", "查询完成", false)));
        List<String> toolOutputs = new ArrayList<>();

        adapter(toolRunner).runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(observer(toolOutputs, new AtomicBoolean()));

        assertThat(toolOutputs).containsExactly("正在查询", "查询完成");
    }

    @Test
    void streamingMapsAdkConfirmationCallToProjectEventWithoutRawArguments() {
        Runner runner = runner(session());
        FunctionCall confirmationCall = FunctionCall.builder()
                .id("confirmation-call")
                .name("adk_request_confirmation")
                .args(Map.of(
                        "originalFunctionCall", Map.of("name", "request_refund", "args", Map.of("orderId", "o-1")),
                        "toolConfirmation", Map.of("hint", "请确认退款")))
                .build();
        Event confirmationEvent = Event.builder()
                .id("confirmation-event")
                .invocationId("invocation-a")
                .author("agent-a")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.builder().functionCall(confirmationCall).build()))
                        .build())
                .build();
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.just(confirmationEvent));

        AtomicReference<ChatEvent> received = new AtomicReference<>();
        adapter(runner).runStream("agent-a", "user-a", "session-a", "refund")
                .subscribe(new ChatStreamObserver() {
                    @Override
                    public void onEvent(ChatEvent event) {
                        received.set(event);
                    }

                    @Override
                    public void onError(Throwable error) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        assertThat(received.get().isToolConfirmation()).isTrue();
        assertThat(received.get().confirmation().toolName()).isEqualTo("request_refund");
        assertThat(received.get().confirmation().message()).isEqualTo("请确认退款");
        assertThat(received.get().confirmation().adkCallId()).isEqualTo("confirmation-call");
    }

    @Test
    void streamingMapsTypedAdkConfirmationObjects() {
        Runner runner = runner(session());
        FunctionCall originalCall = FunctionCall.builder()
                .id("refund-call")
                .name("request_refund")
                .args(Map.of())
                .build();
        ToolConfirmation toolConfirmation = ToolConfirmation.builder()
                .hint("请确认退款")
                .confirmed(false)
                .payload(Map.of("orderId", "o-1"))
                .build();
        FunctionCall confirmationCall = FunctionCall.builder()
                .id("confirmation-call")
                .name("adk_request_confirmation")
                .args(Map.of(
                        "originalFunctionCall", originalCall,
                        "toolConfirmation", toolConfirmation))
                .build();
        Event confirmationEvent = Event.builder()
                .id("confirmation-event")
                .invocationId("invocation-a")
                .author("agent-a")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.builder().functionCall(confirmationCall).build()))
                        .build())
                .build();
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.just(confirmationEvent));

        AtomicReference<ChatEvent> received = new AtomicReference<>();
        adapter(runner).runStream("agent-a", "user-a", "session-a", "refund")
                .subscribe(new ChatStreamObserver() {
                    @Override
                    public void onEvent(ChatEvent event) {
                        received.set(event);
                    }

                    @Override
                    public void onError(Throwable error) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        assertThat(received.get().confirmation().toolName()).isEqualTo("request_refund");
        assertThat(received.get().confirmation().message()).isEqualTo("请确认退款");
    }

    @Test
    void syncDoesNotExposeToolProtocolEventsAsAssistantText() {
        Runner runner = runner(session());
        Event ordinaryFunctionCall = Event.builder()
                .id("tool-call")
                .invocationId("invocation-a")
                .author("agent-a")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromFunctionCall("lookup", Map.of("key", "value"))))
                        .build())
                .build();
        Event ordinaryFunctionResponse = Event.builder()
                .id("tool-response")
                .invocationId("invocation-a")
                .author("agent-a")
                .content(Content.builder()
                        .role("user")
                        .parts(List.of(Part.builder()
                                .functionResponse(FunctionResponse.builder()
                                        .id("tool-call")
                                        .name("lookup")
                                        .response(Map.of("result", "ok"))
                                        .build())
                                .build()))
                        .build())
                .build();
        FunctionCall confirmationCall = FunctionCall.builder()
                .id("confirmation-call")
                .name("adk_request_confirmation")
                .args(Map.of(
                        "originalFunctionCall", Map.of("name", "request_refund"),
                        "toolConfirmation", Map.of("hint", "请确认退款")))
                .build();
        Event hitlConfirmation = Event.builder()
                .id("confirmation-event")
                .invocationId("invocation-a")
                .author("agent-a")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.builder().functionCall(confirmationCall).build()))
                        .build())
                .build();
        Event finalText = textEvent("final-a", "您的退款申请正在等待人工审批。", false);
        when(runner.runAsync(eq("user-a"), eq("session-a"), any(Content.class), any(RunConfig.class)))
                .thenReturn(Flowable.just(ordinaryFunctionCall, ordinaryFunctionResponse, hitlConfirmation, finalText));

        ChatResult result = adapter(runner).run("agent-a", "user-a", "session-a", "refund");

        assertThat(result.contents()).containsExactly("您的退款申请正在等待人工审批。");
        assertThat(result.pendingConfirmations())
                .singleElement()
                .extracting(request -> request.toolName())
                .isEqualTo("request_refund");
    }

    private ChatStreamObserver observer(List<String> outputs, AtomicBoolean completed) {
        return new ChatStreamObserver() {
            @Override
            public void onEvent(ChatEvent event) {
                outputs.add(event.content());
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        };
    }

    private Event textEvent(String id, String text, boolean partial) {
        return Event.builder()
                .id(id)
                .author("agent-a")
                .content(Content.builder()
                        .role("model")
                        .parts(List.of(Part.fromText(text)))
                        .build())
                .partial(partial)
                .build();
    }

    private AdkAgentRuntimeAdapter adapter(Runner runner) {
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "test", runner, RunConfig.builder().build())));
        return new AdkAgentRuntimeAdapter(
                registry, new SessionInvocationCoordinator(Runnable::run));
    }

    private AdkAgentRuntimeAdapter adapterWithCleanup(Runner runner) {
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a",
                "agent-a",
                "Agent A",
                "test",
                runner,
                RunConfig.builder().build(),
                new RuntimeCompactionPlan(
                        ContextCompressTypeEnum.FIXED_SIZE,
                        mock(EventCompactor.class),
                        Integer.MAX_VALUE,
                        true))));
        return new AdkAgentRuntimeAdapter(
                registry, new SessionInvocationCoordinator(Runnable::run));
    }

    private Runner runner(Session session) {
        Runner runner = mock(Runner.class);
        BaseSessionService sessionService = mock(BaseSessionService.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(sessionService.getSession(any(), any(), any(), any())).thenReturn(Maybe.just(session));
        return runner;
    }

    private Session session() {
        return Session.builder("session-a")
                .appName("app-a")
                .userId("user-a")
                .events(new ArrayList<>())
                .build();
    }
}
