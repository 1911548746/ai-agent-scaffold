package com.hh.domain.agent.service;

import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;

import java.util.List;

public interface IArmoryService {

    /**
     * 在调用方的临时编译阶段构建运行时；不得在实现内部发布 Spring Bean。
     */
    List<AiAgentRegisterVO> acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
