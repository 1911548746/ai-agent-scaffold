package com.hh.domain.agent.adapter.repository;

import com.hh.domain.agent.model.enums.MessageStatus;

public interface IMessageArchiveRepository {

    void saveUserMessage(Long conversationId, String appName, String userId, String sessionId, String text);

    void saveAssistantMessage(Long conversationId, String appName, String userId, String sessionId, String text);

    /**
     * 在订阅流式响应前先写入 pending 消息，使取消和失败也能保留正确的历史语义。
     */
    Long createPendingAssistantMessage(Long conversationId, String appName, String userId, String sessionId);

    void completeAssistantMessage(Long conversationId, Long messageId, String text);

    void updateAssistantMessageStatus(Long conversationId, Long messageId, MessageStatus status);

}
