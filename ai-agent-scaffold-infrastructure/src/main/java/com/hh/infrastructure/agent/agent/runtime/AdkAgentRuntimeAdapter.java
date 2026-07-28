package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.events.Event;
import com.google.adk.agents.RunConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.infrastructure.agent.agent.runtime.invocation.ModelInvocationFailureMapper;
import com.hh.infrastructure.agent.agent.runtime.invocation.SessionInvocationCoordinator;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Google ADK Runner 的运行时适配器。
 *
 * <p>此处是唯一读取 ADK Runtime 快照并调用 Runner 的位置。application 只面向
 * {@link AgentRuntimePort} 编排消息归档和状态，不再持有 SDK Runner。</p>
 */
@Service
public class AdkAgentRuntimeAdapter implements AgentRuntimePort {

    private final AdkAgentRuntimeRegistry runtimeRegistry;
    private final SessionInvocationCoordinator compactionCoordinator;

    @Autowired
    public AdkAgentRuntimeAdapter(
            AdkAgentRuntimeRegistry runtimeRegistry,
            SessionInvocationCoordinator compactionCoordinator) {
        this.runtimeRegistry = runtimeRegistry;
        this.compactionCoordinator = compactionCoordinator;
    }

    /** 保持既有离线测试和非 Spring 使用方的构造方式。 */
    public AdkAgentRuntimeAdapter(AdkAgentRuntimeRegistry runtimeRegistry) {
        this(runtimeRegistry, SessionInvocationCoordinator.direct());
    }

    @Override
    public List<AgentDescriptor> listAgents() {
        return runtimeRegistry.snapshot().values().stream()
                .map(AdkAgentRuntime::toAgentDescriptor)
                .toList();
    }

    @Override
    public String appName(String agentId) {
        return requireRuntime(agentId).appName();
    }

    @Override
    public String createSession(String agentId, String userId) {
        AdkAgentRuntime runtime = requireRuntime(agentId);
        Session session = runtime.runner().sessionService()
                .createSession(runtime.appName(), userId)
                .blockingGet();
        return session.id();
    }

    @Override
    public ChatResult run(String agentId, String userId, String sessionId, String message) {
        List<String> outputs = new ArrayList<>();
        AdkAgentRuntime runtime = requireRuntime(agentId);
        Content content = Content.fromParts(Part.fromText(message));
        SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                runtime, userId, sessionId, content);
        try {
            runAsync(runtime, userId, sessionId, content, runtime.runConfig())
                    .blockingForEach(event -> appendFinalResponse(outputs, event));
            lease.success();
            return new ChatResult(outputs);
        } catch (RuntimeException | Error error) {
            lease.fail();
            throw error;
        }
    }

    @Override
    public ChatStream runStream(String agentId, String userId, String sessionId, String message) {
        AdkAgentRuntime runtime = requireRuntime(agentId);
        RunConfig runConfig = runtime.runConfig().toBuilder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        Content content = Content.fromParts(Part.fromText(message));
        return observer -> {
            SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                    runtime, userId, sessionId, content);
            try {
                Flowable<Event> events = runAsync(runtime, userId, sessionId, content, runConfig);
                var disposable = events.subscribe(
                // TODO 后期可以加入 思考过程、工具调用 等 trace，在前端展示
                event -> {
                    // FunctionCall、FunctionResponse 等 SDK 内部事件不是对话协议的一部分。
                    if (event.finalResponse()) {
                        observer.onEvent(new ChatEvent(event.stringifyContent()));
                    }
                },
                error -> {
                    lease.fail();
                    observer.onError(error);
                },
                () -> {
                    lease.success();
                    observer.onComplete();
                }
                );
                return () -> {
                    // 先停止上游继续产出 Event，再在同一 Session 许可内清理本轮已落地的上下文。
                    disposable.dispose();
                    lease.fail();
                };
            } catch (RuntimeException | Error error) {
                lease.fail();
                throw error;
            }
        };
    }

    @Override
    public ChatResult run(String agentId, ChatCommandEntity command) {
        List<Part> parts = new ArrayList<>();
        if (command.getTexts() != null) {
            command.getTexts().forEach(text -> parts.add(Part.fromText(text.getMessage())));
        }
        if (command.getFiles() != null) {
            command.getFiles().forEach(file -> parts.add(Part.fromUri(file.getFileUri(), file.getMimeType())));
        }
        if (command.getInlineDatas() != null) {
            command.getInlineDatas().forEach(data -> parts.add(Part.fromBytes(data.getBytes(), data.getMimeType())));
        }

        AdkAgentRuntime runtime = requireRuntime(agentId);
        Content content = Content.builder().parts(parts).build();
        SessionInvocationCoordinator.InvocationLease lease = compactionCoordinator.begin(
                runtime, command.getUserId(), command.getSessionId(), content);
        List<String> outputs = new ArrayList<>();
        try {
            runAsync(runtime, command.getUserId(), command.getSessionId(), content, runtime.runConfig())
                    .blockingForEach(event -> appendFinalResponse(outputs, event));
            lease.success();
            return new ChatResult(outputs);
        } catch (RuntimeException | Error error) {
            lease.fail();
            throw error;
        }
    }

    /**
     * 统一建立 ADK 响应式调用，并在错误通道中完成模型异常映射。
     *
     * <p>{@code runAsync()} 返回后发生的超时不会被普通 try/catch 捕获，必须在 Flowable 的
     * onError 通道转换；同时保留建立 Flowable 时同步抛错的防御分支。</p>
     */
    private Flowable<Event> runAsync(
            AdkAgentRuntime runtime,
            String userId,
            String sessionId,
            Content content,
            RunConfig runConfig) {
        try {
            return runtime.runner()
                    .runAsync(userId, sessionId, content, runConfig)
                    .onErrorResumeNext(error -> Flowable.error(ModelInvocationFailureMapper.map(error)));
        } catch (RuntimeException error) {
            Throwable mapped = ModelInvocationFailureMapper.map(error);
            throw mapped instanceof RuntimeException runtimeException ? runtimeException : error;
        }
    }

    private void appendFinalResponse(List<String> outputs, Event event) {
        if (event.finalResponse()) {
            outputs.add(event.stringifyContent());
        }
    }

    /**
     * Registry 中不存在时返回稳定错误码，避免把容器或 SDK 查找异常暴露到 HTTP 层。
     */
    private AdkAgentRuntime requireRuntime(String agentId) {
        return runtimeRegistry.find(agentId)
                .orElseThrow(() -> new AppException(ResponseCode.AGENT_NOT_FOUND));
    }
}
