package com.hh.application.agent.usecase;

import com.hh.application.agent.model.ConversationDeletionCommand;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;

import java.util.List;

/**
 * 会话归档查询与删除的入站用例。
 */
public interface ConversationArchiveUseCase {

    List<ConversationVO> listConversations(String userId);

    ConversationVO getConversation(Long conversationId);

    default ConversationVO getConversation(Long conversationId, String userId) {
        ConversationVO conversation = getConversation(conversationId);
        if (conversation == null || userId == null || !userId.equals(conversation.getUserId())) {
            throw new com.hh.types.exception.AppException(com.hh.types.enums.ResponseCode.ACCESS_DENIED);
        }
        return conversation;
    }

    List<MessageVO> listMessages(Long conversationId);

    default List<MessageVO> listMessages(Long conversationId, String userId) {
        getConversation(conversationId, userId);
        return listMessages(conversationId);
    }

    /**
     * 删除归档 Conversation 与同一身份下的 ADK Session/Event。
     */
    void deleteConversation(ConversationDeletionCommand command);
}
