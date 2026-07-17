package com.hh.api;

import com.hh.api.dto.ConversationDeleteRequestDTO;
import com.hh.api.dto.ConversationDetailResponseDTO;
import com.hh.api.dto.ConversationListResponseDTO;
import com.hh.api.response.Response;

import java.util.List;

public interface IConversationService {

    Response<List<ConversationListResponseDTO>> listConversations(String userId);

    Response<ConversationDetailResponseDTO> getConversationDetail(Long conversationId);

    Response<Void> deleteConversation(ConversationDeleteRequestDTO requestDTO);

}
