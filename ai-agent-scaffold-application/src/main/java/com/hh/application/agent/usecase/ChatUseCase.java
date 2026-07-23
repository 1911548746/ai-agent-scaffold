package com.hh.application.agent.usecase;

import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;

import java.util.List;

/**
 * Agent 对话的入站用例。
 *
 * <p>入站适配器通过项目内 {@link ChatResult} 与 {@link ChatStream} 调用本用例；SSE、RxJava
 * 和 ADK 等技术细节只允许停留在各自的适配层。</p>
 */
public interface ChatUseCase {

    List<AgentDescriptor> queryAgentList();

    String createSession(String agentId, String userId);

    ChatResult handleMessage(String agentId, String userId, String sessionId, String message);

    ChatStream handleMessageStream(String agentId, String userId, String sessionId, String message);

    ChatResult handleMessage(ChatCommandEntity chatCommandEntity);

    void deleteSession(String agentId, String userId, String sessionId);
}
