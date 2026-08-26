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

    /** 按对外 UUID 查询当前用户所属会话。 */
    ConversationVO getConversation(String conversationId, String userId);

    /** 按对外 UUID 查询当前用户所属会话的消息。 */
    List<MessageVO> listMessages(String conversationId, String userId);

    /**
     * 删除归档 Conversation 与同一身份下的 ADK Session/Event。
     */
    void deleteConversation(ConversationDeletionCommand command);
}
