package com.hh.infrastructure.agent.agent.runtime.invocation;

import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.reactivex.rxjava3.core.Maybe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 失败或取消 invocation 的 Event 补偿器。
 *
 * <p>Runner 可能已经把部分工具调用、状态变更或模型输出写入 Session。这里先依据调用前快照找出
 * 本轮新增 Event，再区分“可以整轮丢弃”和“必须保留进度并补齐中断轨迹”两种情况。持久化实现仍由
 * {@link AdkSessionStore} 负责事务边界，本类不直接接触 DAO。</p>
 */
@Component
public class InvocationFailureRecovery {

    private static final String INTERRUPTED_TOOL_RESPONSE =
            "Tool execution was interrupted. Verify external state before retrying this operation.";
    private static final String INTERRUPTED_INVOCATION_MESSAGE =
            "The previous invocation was interrupted after producing execution progress. "
                    + "Use the preserved tool results and verify external state before continuing.";

    /**
     * 根据调用前快照整理失败 invocation 产生的所有 Event。
     *
     * <p>没有可恢复执行进度的 invocation 整轮删除；已经进入 Tool 或状态变更阶段的 invocation
     * 则保留进度，并补充中断轨迹供下一轮继续判断。</p>
     */
    public void recover(
            AdkAgentRuntime runtime,
            String userId,
            String sessionId,
            InvocationBaseline baseline) {
        // 重新加载最新 Session，不能使用调用前对象，因为 Runner 可能在异步阶段追加了 Event。
        Session session = loadSession(runtime, userId, sessionId);
        List<Event> newEvents = baseline.newEvents(session.events());
        if (newEvents.isEmpty()) {
            return;
        }

        // 一个 Runner 调用可能产生多个 invocation，必须逐个分类，不能按整批 Event 统一删除。
        Map<String, List<Event>> eventsByInvocation = new LinkedHashMap<>();
        for (Event event : newEvents) {
            if (hasText(event.invocationId())) {
                eventsByInvocation.computeIfAbsent(event.invocationId(), ignored -> new ArrayList<>()).add(event);
            }
        }

        // 先保留有进度的 invocation，再集中删除无进度 invocation，避免按整批 Event 错误处理。
        List<String> discardInvocationIds = new ArrayList<>();
        for (Map.Entry<String, List<Event>> entry : eventsByInvocation.entrySet()) {
            if (!hasRecoverableProgress(entry.getValue())) {
                discardInvocationIds.add(entry.getKey());
                continue;
            }
            preserveInterruptedInvocation(session, runtime, entry.getKey(), entry.getValue());
        }
        if (!discardInvocationIds.isEmpty()) {
            discardInvocations(session, runtime, discardInvocationIds);
        }
    }

    /** 删除没有产生可恢复进度的 invocation；持久化 Session 由 ADK SessionStore 负责事务处理。 */
    private void discardInvocations(Session session, AdkAgentRuntime runtime, List<String> invocationIds) {
        if (runtime.runner().sessionService() instanceof AdkSessionStore sessionStore) {
            sessionStore.discardInvocationEvents(session, invocationIds).blockingAwait();
        } else {
            session.events().removeIf(event -> invocationIds.contains(event.invocationId()));
        }
    }

    /** 保存工具进度和中断标记，并删除本轮中可以安全丢弃的中间 Event。 */
    private void preserveInterruptedInvocation(
            Session session,
            AdkAgentRuntime runtime,
            String invocationId,
            List<Event> invocationEvents) {
        InterruptedInvocationPlan recoveryPlan = buildInterruptedPlan(runtime, invocationId, invocationEvents);
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

    /** 将失败 invocation 转换成“删除集合 + 恢复 Event”计划，不直接触碰存储层。 */
    private InterruptedInvocationPlan buildInterruptedPlan(
            AdkAgentRuntime runtime,
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
                .filter(InvocationFailureRecovery::hasText)
                .distinct()
                .toList();

        List<Event> recoveryEvents = new ArrayList<>();
        List<FunctionCall> unmatchedCalls = unmatchedFunctionCalls(invocationEvents);
        if (!unmatchedCalls.isEmpty()) {
            recoveryEvents.add(interruptedFunctionResponseEvent(runtime, invocationId, unmatchedCalls));
        }
        recoveryEvents.add(interruptedMarkerEvent(runtime, invocationId));
        return new InterruptedInvocationPlan(deletableEventIds, List.copyOf(recoveryEvents));
    }

    /** 判断失败 invocation 中哪些 Event 只是中间模型输出，可以被安全删除。 */
    private boolean shouldDiscardInterruptedEvent(Event event, Event userEvent) {
        if (event == userEvent || hasActionProgress(event)) {
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

    /** 找出没有对应 FunctionResponse 的调用，后续为其补充中断响应。 */
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

    /** 优先按 FunctionCall ID 匹配响应，缺少 ID 时再按函数名兜底。 */
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

    /** 创建 ADK 可识别的工具中断响应 Event，保留原调用的 ID 和名称。 */
    private Event interruptedFunctionResponseEvent(
            AdkAgentRuntime runtime,
            String invocationId,
            List<FunctionCall> unmatchedCalls) {
        List<Part> responseParts = unmatchedCalls.stream()
                .map(this::interruptedFunctionResponsePart)
                .toList();
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId(invocationId)
                .author(runtimeAgentName(runtime))
                .content(Content.builder().role("user").parts(responseParts).build())
                .interrupted(true)
                .timestamp(Instant.now().getEpochSecond())
                .build();
    }

    /** 为未完成的工具调用构造固定错误信息，避免伪造工具执行结果。 */
    private Part interruptedFunctionResponsePart(FunctionCall call) {
        FunctionResponse.Builder response = FunctionResponse.builder()
                .response(Map.of("error", INTERRUPTED_TOOL_RESPONSE));
        call.id().ifPresent(response::id);
        call.name().ifPresent(response::name);
        return Part.builder().functionResponse(response).build();
    }

    /** 创建模型可理解的中断标记，提示下一轮沿用已保留的执行进度。 */
    private Event interruptedMarkerEvent(AdkAgentRuntime runtime, String invocationId) {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId(invocationId)
                .author(runtimeAgentName(runtime))
                .content(Content.builder()
                        .role("model")
                        .parts(Part.fromText(INTERRUPTED_INVOCATION_MESSAGE))
                        .build())
                .interrupted(true)
                .turnComplete(true)
                .timestamp(Instant.now().getEpochSecond())
                .build();
    }

    /** 加载失败补偿所需的最新 Session，并统一处理 Session 不存在的情况。 */
    private Session loadSession(AdkAgentRuntime runtime, String userId, String sessionId) {
        Session session = runtime.runner().sessionService()
                .getSession(runtime.appName(), userId, sessionId, Optional.empty())
                .blockingGet();
        if (session == null) {
            throw new AppException(ResponseCode.SESSION_NOT_FOUND);
        }
        return session;
    }

    private String runtimeAgentName(AdkAgentRuntime runtime) {
        return runtime.runner().agent() == null ? runtime.agentId() : runtime.runner().agent().name();
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

    private record InterruptedInvocationPlan(List<String> deletableEventIds, List<Event> recoveryEvents) {
    }
}
