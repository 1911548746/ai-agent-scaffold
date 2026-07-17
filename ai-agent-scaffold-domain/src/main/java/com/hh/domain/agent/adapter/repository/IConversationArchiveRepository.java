package com.hh.domain.agent.adapter.repository;

import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;

import java.util.List;

public interface IConversationArchiveRepository {

    Long findOrCreateConversation(String appName, String userId, String sessionId);

    List<ConversationVO> listConversations(String userId);

    ConversationVO getConversation(Long conversationId);

    List<MessageVO> listMessages(Long conversationId);

    void deleteConversation(Long conversationId);

}
