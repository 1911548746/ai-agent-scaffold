package com.hh.application.agent.port;

import com.google.adk.events.Event;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话用例访问已发布 Agent 运行时的端口。
 *
 * <p>当前为了保持既有 SSE 契约，流式返回值仍使用 ADK/RxJava 类型；ARC-03 将把它替换为
 * 项目自有流式事件类型。Runner 本身不会越过此端口暴露给 application。</p>
 */
public interface AgentRuntimePort {

    List<AgentDescriptor> listAgents();

    String appName(String agentId);

    String createSession(String agentId, String userId);

    List<String> run(String agentId, String userId, String sessionId, String message);

    Flowable<Event> runStream(String agentId, String userId, String sessionId, String message);

    List<String> run(String agentId, ChatCommandEntity command);

    void deleteSession(String agentId, String userId, String sessionId);
}
