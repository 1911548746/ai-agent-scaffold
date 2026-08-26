package com.hh.api.agent;

import com.hh.api.agent.dto.conversation.ConversationDeleteRequestDTO;
import com.hh.api.agent.dto.conversation.ConversationDetailResponseDTO;
import com.hh.api.agent.dto.conversation.ConversationListResponseDTO;
import com.hh.api.agent.response.Response;

import java.util.List;

public interface IConversationService {

    Response<List<ConversationListResponseDTO>> listConversations();

    Response<ConversationDetailResponseDTO> getConversationDetail(String conversationId);

    Response<Void> deleteConversation(ConversationDeleteRequestDTO requestDTO);

}
