package com.hh.application.agent.usecase;

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

    void deleteConversation(Long conversationId);
}
