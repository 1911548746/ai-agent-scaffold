package com.hh.infrastructure.agent.runtime;

import com.google.adk.events.Event;
import com.google.adk.agents.RunConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
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
    public List<String> run(String agentId, String userId, String sessionId, String message) {
        List<String> outputs = new ArrayList<>();
        requireRuntime(agentId).runner()
                .runAsync(userId, sessionId, Content.fromParts(Part.fromText(message)))
                .blockingForEach(event -> appendFinalResponse(outputs, event));
        return outputs;
    }

    @Override
    public Flowable<Event> runStream(String agentId, String userId, String sessionId, String message) {
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();
        return requireRuntime(agentId).runner().runAsync(
                userId, sessionId, Content.fromParts(Part.fromText(message)), runConfig);
    }

    @Override
    public List<String> run(String agentId, ChatCommandEntity command) {
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
        requireRuntime(agentId).runner()
                .runAsync(command.getUserId(), command.getSessionId(), Content.builder().parts(parts).build())
                .blockingForEach(event -> appendFinalResponse(outputs, event));
        return outputs;
    }

    @Override
    public void deleteSession(String agentId, String userId, String sessionId) {
        AdkAgentRuntime runtime = requireRuntime(agentId);
        runtime.runner().sessionService()
                .deleteSession(runtime.appName(), userId, sessionId)
                .blockingAwait();
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
