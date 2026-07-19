package com.hh.domain.agent.service;

import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;

import java.util.List;

public interface IArmoryService {

    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
