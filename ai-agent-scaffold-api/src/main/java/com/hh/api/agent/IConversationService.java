package com.hh.api.agent;

import com.hh.api.agent.dto.ConversationDeleteRequestDTO;
import com.hh.api.agent.dto.ConversationDetailResponseDTO;
import com.hh.api.agent.dto.ConversationListResponseDTO;
import com.hh.api.agent.response.Response;

import java.util.List;

public interface IConversationService {

    Response<List<ConversationListResponseDTO>> listConversations(String userId);

    Response<ConversationDetailResponseDTO> getConversationDetail(Long conversationId);

    Response<Void> deleteConversation(ConversationDeleteRequestDTO requestDTO);

}
