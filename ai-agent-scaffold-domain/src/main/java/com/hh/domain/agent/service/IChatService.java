package com.hh.domain.agent.service;

import com.google.adk.events.Event;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.valobj.AgentInfo;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

public interface IChatService {

    List<AgentInfo> queryAgentList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    void deleteSession(String agentId, String userId, String sessionId);

}
