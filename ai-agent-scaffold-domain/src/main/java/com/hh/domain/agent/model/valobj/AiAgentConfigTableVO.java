package com.hh.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;

@Data
public class AiAgentConfigTableVO {

    // 应用名称
    private String appName;

    // 智能体信息
    private AgentInfo agentInfo;

    // 智能体模块
    private Module module;

    @Data
    public static class AgentInfo {
        private String agentId;
        private String agentName;
        private String agentDesc;
    }

    @Data
    public static class ChatModel {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Long timeout = 90L;
        private Integer maxRetries = 4;
    }
    @Data
    public static class StreamingChatModel {
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private Long timeout = 90L;
    }
    @Data
    public static class LlmModel {
        private ChatModel chatModel;
        private StreamingChatModel streamingChatModel;
        private String modelName;
    }
    @Data
    public static class LlmAgent {
        private String name;
        private String description;
        private String instruction;
        private String outputKey;
    }
    @Data
    public static class AgentWorkflow {
        /**
         * 类型；loop、parallel、sequential
         */
        private String type;
        private String name;
        private List<String> subAgents;
        private String description;
        private Integer maxIterations = 3;

    }

    @Data
    public static class Module {
//        private ChatModel chatModel;
        private LlmModel llmModel;
        private List<LlmAgent> agents;
        private List<AgentWorkflow> agentWorkflows;
    }

}
