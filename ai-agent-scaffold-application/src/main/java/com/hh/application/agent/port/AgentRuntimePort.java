package com.hh.application.agent.port;

import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.runtime.AgentDescriptor;

import java.util.List;

/**
 * 对话用例访问已发布 Agent 运行时的端口。
 *
 * <p>端口仅暴露项目内的结果和流式契约。具体 Runner、ADK Event 与响应式实现都必须由
 * infrastructure 在此端口后适配，不能穿透到 application。</p>
 */
public interface AgentRuntimePort {

    List<AgentDescriptor> listAgents();

    String appName(String agentId);

    String createSession(String agentId, String userId);

    ChatResult run(String agentId, String userId, String sessionId, String message);

    ChatStream runStream(String agentId, String userId, String sessionId, String message);

    ChatResult run(String agentId, ChatCommandEntity command);

}
