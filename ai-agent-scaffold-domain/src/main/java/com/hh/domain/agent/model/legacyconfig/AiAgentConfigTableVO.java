package com.hh.domain.agent.model.legacyconfig;

import lombok.Data;

@Data
public class AiAgentConfigTableVO {

    // 应用名称
    private String appName;

    // 智能体信息
    private AgentInfo agentInfo;

    // 智能体模块
    private Module module;

}
