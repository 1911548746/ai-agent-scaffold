package com.hh.domain.agent.service;

import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;

import java.util.List;

public interface IConversationArchiveService {

    List<ConversationVO> listConversations(String userId);

    ConversationVO getConversation(Long conversationId);

    List<MessageVO> listMessages(Long conversationId);

    void deleteConversation(Long conversationId);

}
