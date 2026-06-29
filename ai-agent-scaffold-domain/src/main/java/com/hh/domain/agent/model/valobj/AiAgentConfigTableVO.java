package com.hh.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

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
    public static class LlmAgent {
        private String name;
        private String description;
        private String instruction;
        private String outputKey;
        private List<McpTool> mcpToolList;
    }

    @Data
    public static class McpTool {

        private SSEServerParameters sse;
        private HttpServerParameters http;
        private StdioServerParameters stdio;
        private LocalServerParameters local;

        @Data
        public static class SSEServerParameters {
            private String name;
            private String url;
            private String sseEndpoint;
            private Long timeout = 90L;
            private Long requestTimeout = 120L;
        }

        @Data
        public static class HttpServerParameters {
            private String name;
            private String url;
            private Map<String, String> headers;
            private Long timeout = 90L;
            private Long requestTimeout = 120L;
        }

        @Data
        public static class StdioServerParameters {
            private String name;
            private String command;
            private List<String> args;
            private Map<String, String> env;

        }

        @Data
        public static class LocalServerParameters {
            private String beanName;
            private String methodName;
        }

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
    public static class Runner {
        private String agentName;
    }

    @Data
    public static class Module {
        private ChatModel chatModel;
        private List<LlmAgent> agents;
        private List<AgentWorkflow> agentWorkflows;
        private Runner runner;
    }

}
