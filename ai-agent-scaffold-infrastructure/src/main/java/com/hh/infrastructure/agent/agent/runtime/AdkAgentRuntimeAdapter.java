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
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import io.reactivex.rxjava3.core.Flowable;
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

    public AdkAgentRuntimeAdapter(AdkAgentRuntimeRegistry runtimeRegistry) {
        this.runtimeRegistry = runtimeRegistry;
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
        runAsync(runtime, userId, sessionId, Content.fromParts(Part.fromText(message)), runtime.runConfig())
                .blockingForEach(event -> appendFinalResponse(outputs, event));
        return new ChatResult(outputs);
    }

    @Override
    public ChatStream runStream(String agentId, String userId, String sessionId, String message) {
        AdkAgentRuntime runtime = requireRuntime(agentId);
        RunConfig runConfig = runtime.runConfig().toBuilder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        Flowable<Event> events = runAsync(
                runtime, userId, sessionId, Content.fromParts(Part.fromText(message)), runConfig);
        return observer -> events.subscribe(
                // TODO 后期可以加入 思考过程、工具调用 等 trace，在前端展示
                event -> {
                    // FunctionCall、FunctionResponse 等 SDK 内部事件不是对话协议的一部分。
                    if (event.finalResponse()) {
                        observer.onEvent(new ChatEvent(event.stringifyContent()));
                    }
                },
                observer::onError,
                observer::onComplete
        )::dispose;
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

        List<String> outputs = new ArrayList<>();
        AdkAgentRuntime runtime = requireRuntime(agentId);
        runAsync(
                runtime,
                command.getUserId(),
                command.getSessionId(),
                Content.builder().parts(parts).build(),
                runtime.runConfig())
                .blockingForEach(event -> appendFinalResponse(outputs, event));
        return new ChatResult(outputs);
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
