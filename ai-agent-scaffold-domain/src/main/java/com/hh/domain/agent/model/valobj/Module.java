package com.hh.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;

@Data
public class Module {

    private ChatModel chatModel;
    private List<LlmAgent> agents;
    private List<AgentWorkflow> agentWorkflows;
    private Runner runner;

    @Data
    public static class Runner {
        private String agentName;
        private Boolean persistence;
        private List<String> pluginNameList;
    }

}
