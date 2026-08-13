package com.hh.infrastructure.agent.agent.runtime.observability;

import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeAdapter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 为 {@link AgentRuntimePort} 增加调用边界观测的显式装饰器。
 *
 * <p>ADK 适配器只负责 SDK 类型转换；调用结果、首个可交付响应、取消和 Session 创建等
 * application 边界语义集中在本类观测。使用显式装饰器而不是 AOP，是因为流式调用的真正
 * 生命周期发生在 {@link ChatStream#subscribe(ChatStreamObserver)} 之后，方法返回并不代表调用结束。</p>
 */
@Primary
@Component
public class ObservedAgentRuntimePort implements AgentRuntimePort {

    private final AdkAgentRuntimeAdapter delegate;
    private final AgentRuntimeObservability observability;

    public ObservedAgentRuntimePort(
            AdkAgentRuntimeAdapter delegate,
            AgentRuntimeObservability observability) {
        this.delegate = delegate;
        this.observability = observability;
    }

    @Override
    public List<AgentDescriptor> listAgents() {
        return delegate.listAgents();
    }

    @Override
    public String appName(String agentId) {
        return delegate.appName(agentId);
    }

    @Override
    public String createSession(String agentId, String userId) {
        String sessionId = delegate.createSession(agentId, userId);
        // 只在创建成功后计数，且不把 userId、sessionId 等请求身份写入指标。
        observability.recordSessionCreated(agentId);
        return sessionId;
    }

    @Override
    public void requireSession(String agentId, String userId, String sessionId) {
        delegate.requireSession(agentId, userId, sessionId);
    }

    @Override
    public ChatResult run(String agentId, String userId, String sessionId, String message) {
        AgentRuntimeObservability.Invocation invocation = observability.startInvocation(agentId, "sync");
        return invocation.observe(() -> observeResult(
                invocation,
                delegate.run(agentId, userId, sessionId, message)));
    }

    @Override
    public ChatResult runConfirmation(
            String agentId, String userId, String sessionId,
            String confirmationCallId, boolean confirmed) {
        AgentRuntimeObservability.Invocation invocation = observability.startInvocation(agentId, "confirmation");
        return invocation.observe(() -> observeResult(invocation,
                delegate.runConfirmation(agentId, userId, sessionId, confirmationCallId, confirmed)));
    }

    @Override
    public ChatStream runStream(String agentId, String userId, String sessionId, String message) {
        // 保持原有 fail-fast 行为：Agent 不存在时仍在创建流时直接失败。
        ChatStream delegateStream = delegate.runStream(agentId, userId, sessionId, message);
        return observer -> {
            // 流式计时从实际订阅开始，避免把 Controller 构造流后的等待时间算入调用耗时。
            AgentRuntimeObservability.Invocation invocation = observability.startInvocation(agentId, "stream");
            try {
                var subscription = delegateStream.subscribe(observedObserver(observer, invocation));
                return () -> invocation.inScope(() -> {
                    // 终态幂等：完成或异常后的重复取消不能覆盖原始结果，也不能重复取消上游。
                    if (invocation.cancel()) {
                        subscription.cancel();
                    }
                });
            } catch (RuntimeException | Error error) {
                invocation.error(error);
                throw error;
            }
        };
    }

    @Override
    public ChatStream runConfirmationStream(
            String agentId, String userId, String sessionId,
            String confirmationCallId, boolean confirmed) {
        ChatStream delegateStream = delegate.runConfirmationStream(
                agentId, userId, sessionId, confirmationCallId, confirmed);
        return observer -> {
            AgentRuntimeObservability.Invocation invocation = observability.startInvocation(agentId, "confirmation-stream");
            try {
                var subscription = delegateStream.subscribe(observedObserver(observer, invocation));
                return () -> invocation.inScope(() -> {
                    if (invocation.cancel()) {
                        subscription.cancel();
                    }
                });
            } catch (RuntimeException | Error error) {
                invocation.error(error);
                throw error;
            }
        };
    }

    @Override
    public ChatResult run(String agentId, ChatCommandEntity command) {
        AgentRuntimeObservability.Invocation invocation = observability.startInvocation(agentId, "multimodal");
        return invocation.observe(() -> observeResult(invocation, delegate.run(agentId, command)));
    }

    private ChatResult observeResult(
            AgentRuntimeObservability.Invocation invocation,
            ChatResult result) {
        // 同步结果只有在端口返回时才真正可交付，按最终内容数量记录响应事件。
        result.contents().forEach(ignored -> invocation.recordResponse());
        return result;
    }

    private ChatStreamObserver observedObserver(
            ChatStreamObserver delegateObserver,
            AgentRuntimeObservability.Invocation invocation) {
        return new ChatStreamObserver() {
            @Override
            public void onEvent(ChatEvent event) {
                invocation.inScope(() -> {
                    invocation.recordResponse();
                    delegateObserver.onEvent(event);
                });
            }

            @Override
            public void onError(Throwable error) {
                invocation.inScope(() -> {
                    invocation.error(error);
                    delegateObserver.onError(error);
                });
            }

            @Override
            public void onComplete() {
                invocation.inScope(() -> {
                    invocation.success();
                    delegateObserver.onComplete();
                });
            }
        };
    }
}
