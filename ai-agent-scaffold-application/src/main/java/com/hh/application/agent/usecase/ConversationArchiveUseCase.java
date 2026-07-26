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

    List<MessageVO> listMessages(Long conversationId);

    /**
     * 删除归档 Conversation 与同一身份下的 ADK Session/Event。
     */
    void deleteConversation(ConversationDeletionCommand command);
}
