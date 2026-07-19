package com.hh.domain.agent.model.legacyconfig;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class McpTool {

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
