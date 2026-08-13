package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.google.adk.summarizer.EventCompactor;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import com.hh.application.agent.exception.ContextCompactionException;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.invocation.SessionInvocationCoordinator;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.types.enums.ContextCompressTypeEnum;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证压缩维护不再改变主调用成功语义，全程使用 mock ADK 组件，不调用模型或数据库。
 */
class SessionInvocationCoordinatorTest {

    @Test
    void fixedSizePrefersLatestPromptTokenCountOverCharacterEstimate() {
        RuntimeCompactionPlan plan = fixedSizePlan(500);
        Session overThreshold = session(Event.builder()
                .id("usage-a")
                .content(Content.fromParts(Part.fromText("short")))
                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(501)
                        .build())
                .build());
        Session withinThreshold = session(Event.builder()
                .id("usage-b")
                .content(Content.fromParts(Part.fromText("x".repeat(4000))))
                .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                        .promptTokenCount(500)
                        .build())
                .build());

        assertThat(plan.preflightRequired(overThreshold, Content.fromParts(Part.fromText("next")))).isTrue();
        assertThat(plan.preflightRequired(
                withinThreshold, Content.fromParts(Part.fromText("x".repeat(4000))))).isFalse();
    }

    @Test
    void fixedSizeFindsEarlierUsageAndFallsBackToCharacterEstimateWhenUsageIsAbsent() {
        RuntimeCompactionPlan plan = fixedSizePlan(500);
        Session earlierUsage = session(
                Event.builder()
                        .id("usage-a")
                        .usageMetadata(GenerateContentResponseUsageMetadata.builder()
                                .promptTokenCount(501)
                                .build())
                        .build(),
                Event.builder().id("latest-user-a").content(Content.fromParts(Part.fromText("next"))).build());
        Session withoutUsage = session(Event.builder()
                .id("history-a")
                .content(Content.fromParts(Part.fromText("x".repeat(1200))))
                .build());

        assertThat(plan.preflightRequired(earlierUsage, Content.fromParts(Part.fromText("pending")))).isTrue();
        assertThat(plan.preflightRequired(
                withoutUsage, Content.fromParts(Part.fromText("x".repeat(1200))))).isTrue();
    }

    @Test
    void fixedSizeFailureHappensBeforeRunnerCanAppendPendingUserMessage() {
        EventCompactor compactor = mock(EventCompactor.class);
        when(compactor.compact(any(), any())).thenReturn(Completable.error(new IllegalStateException("summary failed")));
        Session session = sessionWithLongHistory();
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(Runnable::run);

        assertThatThrownBy(() -> coordinator.begin(
                runtime(new RuntimeCompactionPlan(ContextCompressTypeEnum.FIXED_SIZE, compactor, 1, true), session),
                "user-a", "session-a", Content.fromParts(Part.fromText("next message"))))
                .isInstanceOf(ContextCompactionException.class);

        // Coordinator 只读取历史 Session；调用失败前没有机会由 Runner 写入当前用户 Event。
        assertThat(session.events()).extracting(Event::id).containsExactly("history-a");
        verify(compactor).compact(any(Session.class), any(BaseSessionService.class));
    }

    @Test
    void missingSessionIsNotMappedToContextCompactionFailure() {
        BaseSessionService sessionService = mock(BaseSessionService.class);
        when(sessionService.getSession(any(), any(), any(), any())).thenReturn(Maybe.empty());
        Runner runner = mock(Runner.class);
        when(runner.sessionService()).thenReturn(sessionService);
        AdkAgentRuntime runtime = new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "test", runner, RunConfig.builder().build(),
                fixedSizePlan(Integer.MAX_VALUE));

        assertThatThrownBy(() -> new SessionInvocationCoordinator(Runnable::run).begin(
                runtime, "user-a", "missing-session", Content.fromParts(Part.fromText("hello"))))
                .isInstanceOf(AppException.class)
                .isNotInstanceOf(ContextCompactionException.class)
                .extracting("code")
                .isEqualTo(ResponseCode.SESSION_NOT_FOUND.getCode());
    }

    @Test
    void slidingWindowSchedulesMaintenanceAfterSuccessWithoutPropagatingFailure() {
        EventCompactor compactor = mock(EventCompactor.class);
        when(compactor.compact(any(), any())).thenReturn(Completable.error(new IllegalStateException("summary failed")));
        QueueExecutor executor = new QueueExecutor();
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(executor);
        AdkAgentRuntime runtime = runtime(new RuntimeCompactionPlan(
                ContextCompressTypeEnum.SLIDING_WINDOW, compactor, 0, true), sessionWithLongHistory());

        SessionInvocationCoordinator.InvocationLease lease = coordinator.begin(
                runtime, "user-a", "session-a", Content.fromParts(Part.fromText("next message")));
        lease.success();

        // 成功路径只投递后台维护；摘要失败不能从 lease.success 反向抛给已生成回复的调用方。
        assertThat(executor.tasks).hasSize(1);
        executor.runAll();
        verify(compactor).compact(any(Session.class), any(BaseSessionService.class));
    }

    @Test
    void failureDiscardsOnlyInvocationsAddedAfterBaselineFromInMemorySession() {
        Session session = session(Event.builder()
                .id("history-a")
                .invocationId("invocation-history")
                .build());
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(Runnable::run);
        SessionInvocationCoordinator.InvocationLease lease = coordinator.begin(
                runtime(fixedSizePlan(Integer.MAX_VALUE), session),
                "user-a", "session-a", Content.fromParts(Part.fromText("next message")));
        session.events().addAll(List.of(
                Event.builder().id("user-a").invocationId("invocation-failed").author("user").build(),
                Event.builder().id("tool-a").invocationId("invocation-failed").author("agent-a").build(),
                Event.builder().id("partial-a").invocationId("invocation-failed").partial(true).build()));

        lease.fail();
        lease.fail();

        assertThat(session.events()).extracting(Event::id).containsExactly("history-a");
    }

    @Test
    void successfulInvocationKeepsNewEvents() {
        Session session = session(Event.builder().id("history-a").invocationId("invocation-history").build());
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(Runnable::run);
        SessionInvocationCoordinator.InvocationLease lease = coordinator.begin(
                runtime(fixedSizePlan(Integer.MAX_VALUE), session),
                "user-a", "session-a", Content.fromParts(Part.fromText("next message")));
        session.events().add(Event.builder().id("reply-a").invocationId("invocation-success").build());

        lease.success();

        assertThat(session.events()).extracting(Event::id).containsExactly("history-a", "reply-a");
    }

    @Test
    void interruptedToolInvocationKeepsRecoverableTraceAndCompletesDanglingCall() {
        Session session = session(Event.builder().id("history-a").invocationId("invocation-history").build());
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(Runnable::run);
        SessionInvocationCoordinator.InvocationLease lease = coordinator.begin(
                runtime(fixedSizePlan(Integer.MAX_VALUE), session),
                "user-a", "session-a", Content.fromParts(Part.fromText("deploy the application")));
        session.events().addAll(List.of(
                textEvent("user-a", "invocation-failed", "user", "deploy the application", false),
                functionCallEvent("call-a", "invocation-failed", "stepA"),
                functionResponseEvent("response-a", "invocation-failed", "call-a", "stepA"),
                functionCallEvent("call-b", "invocation-failed", "stepB"),
                textEvent("partial-a", "invocation-failed", "agent-a", "working", true),
                textEvent("chunk-a", "invocation-failed", "agent-a", "still working", false)));

        lease.fail();

        assertThat(session.events()).extracting(Event::id)
                .contains("history-a", "user-a", "call-a", "response-a", "call-b")
                .doesNotContain("partial-a", "chunk-a");
        List<FunctionResponse> interruptedResponses = session.events().stream()
                .flatMap(event -> event.functionResponses().stream())
                .filter(response -> response.id().filter("call-b"::equals).isPresent())
                .toList();
        assertThat(interruptedResponses)
                .singleElement()
                .satisfies(response -> assertThat(response.response()).isPresent());
        List<Event> interruptionMarkers = session.events().stream()
                .filter(event -> event.interrupted().orElse(false))
                .filter(event -> event.functionResponses().isEmpty())
                .toList();
        assertThat(interruptionMarkers)
                .singleElement()
                .satisfies(marker -> {
                    assertThat(marker.invocationId()).isEqualTo("invocation-failed");
                    assertThat(marker.stringifyContent()).contains("previous invocation was interrupted");
                });
    }

    @Test
    void invocationWithStateProgressIsMarkedInterruptedInsteadOfDiscarded() {
        Session session = session();
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(Runnable::run);
        SessionInvocationCoordinator.InvocationLease lease = coordinator.begin(
                runtime(fixedSizePlan(Integer.MAX_VALUE), session),
                "user-a", "session-a", Content.fromParts(Part.fromText("prepare deployment")));
        session.events().addAll(List.of(
                textEvent("user-a", "invocation-failed", "user", "prepare deployment", false),
                Event.builder()
                        .id("state-a")
                        .invocationId("invocation-failed")
                        .actions(EventActions.builder().stateDelta(Map.of("deploymentStep", "prepared")).build())
                        .build()));

        lease.fail();

        assertThat(session.events()).extracting(Event::id).contains("user-a", "state-a");
        assertThat(session.events()).anyMatch(event -> event.interrupted().orElse(false)
                && event.stringifyContent().contains("previous invocation was interrupted"));
    }

    @Test
    void persistentStoreReceivesExactFailedInvocationAndCleanupFailureIsNotRethrown() {
        Session session = session(Event.builder().id("history-a").invocationId("invocation-history").build());
        AdkSessionStore sessionStore = mock(AdkSessionStore.class);
        when(sessionStore.getSession(any(), any(), any(), any())).thenReturn(Maybe.just(session));
        when(sessionStore.discardInvocationEvents(any(), any()))
                .thenReturn(Completable.error(new IllegalStateException("cleanup failed")));
        SessionInvocationCoordinator coordinator = new SessionInvocationCoordinator(Runnable::run);
        SessionInvocationCoordinator.InvocationLease lease = coordinator.begin(
                runtime(fixedSizePlan(Integer.MAX_VALUE), session, sessionStore),
                "user-a", "session-a", Content.fromParts(Part.fromText("next message")));
        session.events().add(Event.builder().id("user-a").invocationId("invocation-failed").build());

        assertThatCode(lease::fail).doesNotThrowAnyException();
        assertThat(session.events()).extracting(Event::id).containsExactly("history-a", "user-a");
        verify(sessionStore, times(1)).discardInvocationEvents(session, List.of("invocation-failed"));
    }

    private AdkAgentRuntime runtime(RuntimeCompactionPlan plan, Session session) {
        BaseSessionService sessionService = mock(BaseSessionService.class);
        when(sessionService.getSession(any(), any(), any(), any())).thenReturn(Maybe.just(session));
        return runtime(plan, session, sessionService);
    }

    private AdkAgentRuntime runtime(
            RuntimeCompactionPlan plan,
            Session session,
            BaseSessionService sessionService) {
        Runner runner = mock(Runner.class);
        when(runner.sessionService()).thenReturn(sessionService);
        return new AdkAgentRuntime("app-a", "agent-a", "Agent A", "test", runner, RunConfig.builder().build(), plan);
    }

    private RuntimeCompactionPlan fixedSizePlan(int tokenThreshold) {
        return new RuntimeCompactionPlan(
                ContextCompressTypeEnum.FIXED_SIZE,
                mock(EventCompactor.class),
                tokenThreshold,
                true);
    }

    private Session session(Event... events) {
        return Session.builder("session-a")
                .appName("app-a")
                .userId("user-a")
                .events(new ArrayList<>(List.of(events)))
                .build();
    }

    private Session sessionWithLongHistory() {
        return Session.builder("session-a")
                .appName("app-a")
                .userId("user-a")
                .events(new ArrayList<>(List.of(Event.builder()
                        .id("history-a")
                        .content(Content.fromParts(Part.fromText("a very long historical message")))
                        .build())))
                .build();
    }

    private Event textEvent(
            String eventId,
            String invocationId,
            String author,
            String text,
            boolean partial) {
        return Event.builder()
                .id(eventId)
                .invocationId(invocationId)
                .author(author)
                .content(Content.builder().role("user".equals(author) ? "user" : "model")
                        .parts(Part.fromText(text))
                        .build())
                .partial(partial)
                .build();
    }

    private Event functionCallEvent(String callId, String invocationId, String functionName) {
        FunctionCall functionCall = FunctionCall.builder()
                .id(callId)
                .name(functionName)
                .args(Map.of())
                .build();
        return Event.builder()
                .id(callId)
                .invocationId(invocationId)
                .author("agent-a")
                .content(Content.builder().role("model")
                        .parts(Part.builder().functionCall(functionCall).build())
                        .build())
                .build();
    }

    private Event functionResponseEvent(
            String eventId,
            String invocationId,
            String callId,
            String functionName) {
        FunctionResponse functionResponse = FunctionResponse.builder()
                .id(callId)
                .name(functionName)
                .response(Map.of("result", "ok"))
                .build();
        return Event.builder()
                .id(eventId)
                .invocationId(invocationId)
                .author("agent-a")
                .content(Content.builder().role("user")
                        .parts(Part.builder().functionResponse(functionResponse).build())
                        .build())
                .build();
    }

    private static final class QueueExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            tasks.forEach(Runnable::run);
        }
    }
}
