package com.hh.application.agent.service;

import com.hh.application.agent.usecase.ConversationArchiveUseCase;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.util.List;

/**
 * 会话归档用例编排。
 */
public class ConversationArchiveApplicationService implements ConversationArchiveUseCase {

    private final ConversationArchivePort conversationArchivePort;

    public ConversationArchiveApplicationService(ConversationArchivePort conversationArchivePort) {
        this.conversationArchivePort = conversationArchivePort;
    }

    @Override
    public List<ConversationVO> listConversations(String userId) {
        return conversationArchivePort.listConversations(userId);
    }

    @Override
    public ConversationVO getConversation(Long conversationId) {
        ConversationVO conversation = conversationArchivePort.getConversation(conversationId);
        if (conversation == null) {
            // 保持现有接口错误码语义，避免迁移 application 层时改变客户端可见行为。
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }
        return conversation;
    }

    @Override
    public List<MessageVO> listMessages(Long conversationId) {
        return conversationArchivePort.listMessages(conversationId);
    }

    @Override
    public void deleteConversation(Long conversationId) {
        conversationArchivePort.deleteConversation(conversationId);
    }
}
