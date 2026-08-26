package com.hh.api.agent;

import com.hh.api.agent.dto.catalog.AiAgentConfigResponseDTO;
import com.hh.api.agent.dto.chat.ChatRequestDTO;
import com.hh.api.agent.dto.chat.ChatResponseDTO;
import com.hh.api.agent.dto.session.CreateSessionRequestDTO;
import com.hh.api.agent.dto.session.CreateSessionResponseDTO;
import com.hh.api.agent.response.Response;

import java.util.List;

public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

}
