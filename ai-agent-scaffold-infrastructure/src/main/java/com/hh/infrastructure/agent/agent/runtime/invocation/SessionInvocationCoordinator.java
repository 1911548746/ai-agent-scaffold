package com.hh.infrastructure.agent.agent.runtime.invocation;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.hh.application.agent.exception.ContextCompactionException;
import com.hh.application.agent.exception.ModelInvocationException;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.support.SensitiveDataRedactor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 Session 串行协调主调用与上下文维护。
 *
 * <p>fixedSize 在取得许可后、Runner 写入当前用户 Event 前同步预检；slidingWindow 和
 * pureSlidingWindow 在主回复完成后异步维护。失败或取消时，无工具进度的 invocation 会被清理；
 * 已进入工具阶段的 invocation 则保留可恢复轨迹并标记中断。</p>
 */
@Slf4j
@Component
public class SessionInvocationCoordinator {

    private static final String INTERRUPTED_TOOL_RESPONSE =
            "Tool execution was interrupted. Verify external state before retrying this operation.";
    private static final String INTERRUPTED_INVOCATION_MESSAGE =
            "The previous invocation was interrupted after producing execution progress. "
                    + "Use the preserved tool results and verify external state before continuing.";

    private final Executor executor;
    private final ConcurrentMap<SessionKey, Semaphore> permits = new ConcurrentHashMap<>();

    public SessionInvocationCoordinator(@Qualifier("contextCompactionExecutor") Executor executor) {
        this.executor = executor;
    }

    /** 供不启动 Spring 的离线单测和历史 Adapter 构造方式使用。 */
    public static SessionInvocationCoordinator direct() {
        return new SessionInvocationCoordinator(Runnable::run);
    }

    public InvocationLease begin(
            AdkAgentRuntime runtime,
            String userId,
            String sessionId,
            Content pendingUserMessage) {
        RuntimeCompactionPlan plan = runtime.compactionPlan();
        SessionKey key = new SessionKey(runtime.appName(), userId, sessionId);
        Semaphore permit = permits.computeIfAbsent(key, ignored -> new Semaphore(1));
        permit.acquireUninterruptibly();

        try {
            Session session = loadSession(runtime, userId, sessionId);
            if (plan.enabled() && plan.preflightRequired(session, pendingUserMessage)) {
                try {
                    // fixedSize 只压缩既有历史，当前用户消息尚未进入 ADK Session。
                    plan.compact(session, runtime.runner().sessionService()).blockingAwait();
                } catch (RuntimeException error) {
                    // 仅摘要模型或压缩器执行失败才属于上下文压缩不可用。
                    throw new ContextCompactionException(error);
                }
            }
            // 预检可能追加摘要或清理旧 Event，必须在预检完成后记录本轮调用的真实起点。
            InvocationBaseline baseline = InvocationBaseline.capture(session.events());
            return new InvocationLease(key, permit, runtime, userId, sessionId, baseline);
        } catch (RuntimeException | Error error) {
            permit.release();
            throw error;
        }
    }

    private Session loadSession(AdkAgentRuntime runtime, String userId, String sessionId) {
        Session session = runtime.runner().sessionService()
                .getSession(runtime.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            throw new AppException(ResponseCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    public final class InvocationLease {

        private final SessionKey key;
        private final Semaphore permit;
        private final AdkAgentRuntime runtime;
        private final String userId;
        private final String sessionId;
        private final InvocationBaseline baseline;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private InvocationLease(
                SessionKey key,
                Semaphore permit,
                AdkAgentRuntime runtime,
                String userId,
                String sessionId,
                InvocationBaseline baseline) {
            this.key = key;
            this.permit = permit;
            this.runtime = runtime;
            this.userId = userId;
            this.sessionId = sessionId;
            this.baseline = baseline;
        }

        /** 主模型失败或客户端取消时同步整理本轮 Event，再解除当前 Session 的占用。 */
        public void fail() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            try {
                reconcileFailedInvocationEvents();
            } catch (Throwable cleanupError) {
                // 补偿失败不能替换已经确定的模型错误；保留两端状态并输出脱敏诊断信息。
                logInvocationCleanupFailure(key, cleanupError);
            } finally {
                permit.release();
            }
        }

        /**
         * 主回复完成后执行策略规定的维护。后置策略在后台执行，绝不把其异常反向传播给已成功的调用。
         */
        public void success() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            RuntimeCompactionPlan plan = runtime.compactionPlan();
            if (!plan.postResponseRequired()) {
                permit.release();
                return;
            }
            try {
                executor.execute(() -> {
                    try {
                        Session session = loadSession(runtime, userId, sessionId);
                        plan.compact(session, runtime.runner().sessionService()).blockingAwait();
                    } catch (Throwable error) {
                        logPostCompactionFailure(key, error);
                    } finally {
                        permit.release();
                    }
                });
            } catch (RuntimeException executorError) {
                // 线程池拒绝任务时主回复仍然成功；保留原始 Event，后续请求可再次触发维护。
                logPostCompactionFailure(key, executorError);
                permit.release();
            }
        }

        private void reconcileFailedInvocationEvents() {
            Session session = loadSession(runtime, userId, sessionId);
            List<Event> newEvents = baseline.newEvents(session.events());
            if (newEvents.isEmpty()) {
                return;
            }

            Map<String, List<Event>> eventsByInvocation = new LinkedHashMap<>();
            for (Event event : newEvents) {
                if (hasText(event.invocationId())) {
                    eventsByInvocation.computeIfAbsent(event.invocationId(), ignored -> new ArrayList<>()).add(event);
                }
            }

            List<String> discardInvocationIds = new ArrayList<>();
            for (Map.Entry<String, List<Event>> entry : eventsByInvocation.entrySet()) {
                if (!hasRecoverableProgress(entry.getValue())) {
                    discardInvocationIds.add(entry.getKey());
                    continue;
                }
                preserveInterruptedInvocation(session, entry.getKey(), entry.getValue());
            }
            if (!discardInvocationIds.isEmpty()) {
                discardInvocations(session, discardInvocationIds);
            }
        }

        private void discardInvocations(Session session, List<String> invocationIds) {
            if (runtime.runner().sessionService() instanceof AdkSessionStore sessionStore) {
                sessionStore.discardInvocationEvents(session, invocationIds).blockingAwait();
            } else {
                session.events().removeIf(event -> invocationIds.contains(event.invocationId()));
            }
        }

        private void preserveInterruptedInvocation(
                Session session,
                String invocationId,
                List<Event> invocationEvents) {
            InterruptedInvocationPlan recoveryPlan = buildInterruptedPlan(invocationId, invocationEvents);
            if (runtime.runner().sessionService() instanceof AdkSessionStore sessionStore) {
                sessionStore.preserveInterruptedInvocation(
                        session,
                        recoveryPlan.deletableEventIds(),
                        recoveryPlan.recoveryEvents()).blockingAwait();
            } else {
                session.events().removeIf(event -> recoveryPlan.deletableEventIds().contains(event.id()));
                session.events().addAll(recoveryPlan.recoveryEvents());
            }
        }

        private InterruptedInvocationPlan buildInterruptedPlan(
                String invocationId,
                List<Event> invocationEvents) {
            Event userEvent = invocationEvents.stream()
                    .filter(event -> "user".equalsIgnoreCase(event.author()))
                    .filter(event -> event.functionResponses().isEmpty())
                    .findFirst()
                    .orElse(invocationEvents.get(0));
            List<String> deletableEventIds = invocationEvents.stream()
                    .filter(event -> shouldDiscardInterruptedEvent(event, userEvent))
                    .map(Event::id)
                    .filter(SessionInvocationCoordinator::hasText)
                    .distinct()
                    .toList();

            List<Event> recoveryEvents = new ArrayList<>();
            List<FunctionCall> unmatchedCalls = unmatchedFunctionCalls(invocationEvents);
            if (!unmatchedCalls.isEmpty()) {
                recoveryEvents.add(interruptedFunctionResponseEvent(invocationId, unmatchedCalls));
            }
            recoveryEvents.add(interruptedMarkerEvent(invocationId));
            return new InterruptedInvocationPlan(deletableEventIds, List.copyOf(recoveryEvents));
        }

        private boolean shouldDiscardInterruptedEvent(Event event, Event userEvent) {
            if (event == userEvent) {
                return false;
            }
            if (hasActionProgress(event)) {
                return false;
            }
            if (event.partial().orElse(false)) {
                return true;
            }
            if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) {
                return false;
            }
            // 无 Content 的 state/transfer Event 仍可能帮助工作流定位；只清除纯中间模型内容。
            return event.content().isPresent();
        }

        private List<FunctionCall> unmatchedFunctionCalls(List<Event> invocationEvents) {
            List<FunctionResponse> remainingResponses = invocationEvents.stream()
                    .filter(event -> !event.partial().orElse(false))
                    .flatMap(event -> event.functionResponses().stream())
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
            List<FunctionCall> unmatchedCalls = new ArrayList<>();
            invocationEvents.stream()
                    .filter(event -> !event.partial().orElse(false))
                    .flatMap(event -> event.functionCalls().stream())
                    .forEach(call -> {
                        int matchingResponse = matchingResponseIndex(call, remainingResponses);
                        if (matchingResponse >= 0) {
                            remainingResponses.remove(matchingResponse);
                        } else {
                            unmatchedCalls.add(call);
                        }
                    });
            return unmatchedCalls;
        }

        private int matchingResponseIndex(FunctionCall call, List<FunctionResponse> responses) {
            if (call.id().isPresent()) {
                for (int index = 0; index < responses.size(); index++) {
                    if (call.id().equals(responses.get(index).id())) {
                        return index;
                    }
                }
            }
            for (int index = 0; index < responses.size(); index++) {
                if (call.name().isPresent() && call.name().equals(responses.get(index).name())) {
                    return index;
                }
            }
            return -1;
        }

        private Event interruptedFunctionResponseEvent(String invocationId, List<FunctionCall> unmatchedCalls) {
            List<Part> responseParts = unmatchedCalls.stream()
                    .map(this::interruptedFunctionResponsePart)
                    .toList();
            return Event.builder()
                    .id(Event.generateEventId())
                    .invocationId(invocationId)
                    .author(runtimeAgentName())
                    .content(Content.builder().role("user").parts(responseParts).build())
                    .interrupted(true)
                    .timestamp(Instant.now().getEpochSecond())
                    .build();
        }

        private Part interruptedFunctionResponsePart(FunctionCall call) {
            FunctionResponse.Builder response = FunctionResponse.builder()
                    .response(Map.of("error", INTERRUPTED_TOOL_RESPONSE));
            call.id().ifPresent(response::id);
            call.name().ifPresent(response::name);
            return Part.builder().functionResponse(response).build();
        }

        private Event interruptedMarkerEvent(String invocationId) {
            return Event.builder()
                    .id(Event.generateEventId())
                    .invocationId(invocationId)
                    .author(runtimeAgentName())
                    .content(Content.builder()
                            .role("model")
                            .parts(Part.fromText(INTERRUPTED_INVOCATION_MESSAGE))
                            .build())
                    .interrupted(true)
                    .turnComplete(true)
                    .timestamp(Instant.now().getEpochSecond())
                    .build();
        }

        private String runtimeAgentName() {
            return runtime.runner().agent() == null ? runtime.agentId() : runtime.runner().agent().name();
        }
    }

    private void logInvocationCleanupFailure(SessionKey key, Throwable error) {
        log.error("失败 invocation 上下文清理异常，appName: {}，sessionId: {}，异常类型: {}，异常详情: {}",
                key.appName(), key.sessionId(), error.getClass().getName(),
                SensitiveDataRedactor.formatStackTrace(error));
    }

    private void logPostCompactionFailure(SessionKey key, Throwable error) {
        Throwable mapped = ModelInvocationFailureMapper.map(error);
        if (mapped instanceof ModelInvocationException modelError) {
            log.error("后置上下文压缩失败，appName: {}，sessionId: {}，失败类型: {}，根因类型: {}",
                    key.appName(), key.sessionId(), modelError.failureType(), modelError.rootCauseType());
            return;
        }
        log.error("后置上下文压缩异常，appName: {}，sessionId: {}，异常类型: {}，异常详情: {}",
                key.appName(), key.sessionId(), error.getClass().getName(),
                SensitiveDataRedactor.formatStackTrace(error));
    }

    private record SessionKey(String appName, String userId, String sessionId) {
    }

    private record InterruptedInvocationPlan(List<String> deletableEventIds, List<Event> recoveryEvents) {
    }

    /**
     * 记录 Runner 调用前的 Event 边界；非空 eventId 优先用于跨 MySQL 快照比较，数量仅兜底无 ID Event。
     */
    private record InvocationBaseline(Set<String> eventIds, int eventCount) {

        private static InvocationBaseline capture(List<Event> events) {
            Set<String> eventIds = new HashSet<>();
            events.stream()
                    .map(Event::id)
                    .filter(SessionInvocationCoordinator::hasText)
                    .forEach(eventIds::add);
            return new InvocationBaseline(Set.copyOf(eventIds), events.size());
        }

        private List<Event> newEvents(List<Event> events) {
            List<Event> newEvents = new ArrayList<>();
            for (int index = 0; index < events.size(); index++) {
                Event event = events.get(index);
                boolean newEvent = hasText(event.id())
                        ? !eventIds.contains(event.id())
                        : index >= eventCount;
                if (newEvent) {
                    newEvents.add(event);
                }
            }
            return List.copyOf(newEvents);
        }
    }

    private static boolean hasRecoverableProgress(List<Event> events) {
        return events.stream().anyMatch(event -> !event.functionResponses().isEmpty()
                || (!event.partial().orElse(false) && !event.functionCalls().isEmpty())
                || hasActionProgress(event));
    }

    private static boolean hasActionProgress(Event event) {
        return event.actions() != null && (!event.actions().stateDelta().isEmpty()
                || !event.actions().artifactDelta().isEmpty()
                || !event.actions().deletedArtifactIds().isEmpty()
                || event.actions().transferToAgent().isPresent());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
