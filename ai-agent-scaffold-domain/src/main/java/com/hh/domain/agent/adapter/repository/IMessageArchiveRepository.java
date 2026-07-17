package com.hh.domain.agent.adapter.repository;

public interface IMessageArchiveRepository {

    void saveUserMessage(Long conversationId, String appName, String userId, String sessionId, String text);

    void saveAssistantMessage(Long conversationId, String appName, String userId, String sessionId, String text);

}
