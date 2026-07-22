package com.hh.infrastructure.agent.compiler.assembly.matter.mcp.client.impl;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.infrastructure.agent.compiler.assembly.matter.mcp.client.McpToolCreateService;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class HttpMcpToolCreateService implements McpToolCreateService<McpToolset> {
    @Override
    public McpToolset buildMcpToolset(McpToolDefinition mcpToolConfig) {

        McpToolDefinition.HttpServerParameters toolConfigHttp = mcpToolConfig.getHttp();

        StreamableHttpServerParameters httpServer = StreamableHttpServerParameters.builder()
                .url(toolConfigHttp.getUrl())
                .headers(toolConfigHttp.getHeaders())
                .timeout(Duration.ofSeconds(toolConfigHttp.getTimeout()))
                .readTimeout(Duration.ofSeconds(toolConfigHttp.getRequestTimeout()))
                .terminateOnClose(false)
                .build();

        return new McpToolset(httpServer);
    }
}
