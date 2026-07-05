package com.hh.domain.agent.service.armory.matter.mcp.client.impl;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StreamableHttpServerParameters;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.service.armory.matter.mcp.client.McpToolCreateService;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class HttpMcpToolCreateService implements McpToolCreateService<McpToolset> {
    @Override
    public McpToolset buildMcpToolset(AiAgentConfigTableVO.McpTool mcpToolConfig) {

        AiAgentConfigTableVO.McpTool.HttpServerParameters toolConfigHttp = mcpToolConfig.getHttp();

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
