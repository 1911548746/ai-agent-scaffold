package com.hh.application.agent.usecase;

import com.google.adk.events.Event;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * Agent 对话的入站用例。
 *
 * <p>当前流式签名暂保留 ADK 与 RxJava 类型以维持现有 HTTP 行为；ARC-03 会替换为项目自有
 * ChatStream 和 ChatEvent。</p>
 */
public interface ChatUseCase {

    List<AgentDescriptor> queryAgentList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    void deleteSession(String agentId, String userId, String sessionId);
}
