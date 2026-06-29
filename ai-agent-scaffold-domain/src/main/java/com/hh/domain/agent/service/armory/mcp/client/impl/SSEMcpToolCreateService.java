package com.hh.domain.agent.service.armory.mcp.client.impl;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.SseServerParameters;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.service.armory.mcp.client.McpToolCreateService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class SSEMcpToolCreateService implements McpToolCreateService<McpToolset> {
    @Override
    public McpToolset buildMcpToolset(AiAgentConfigTableVO.McpTool mcpToolConfig) {

        AiAgentConfigTableVO.McpTool.SSEServerParameters toolConfigSse = mcpToolConfig.getSse();

        SseServerParameters sseServer = SseServerParameters.builder()
                .url(toolConfigSse.getUrl())
                .sseEndpoint(toolConfigSse.getSseEndpoint())
                .headers(Map.of())
                .timeout(Duration.ofSeconds(toolConfigSse.getTimeout()))
                .sseReadTimeout(Duration.ofSeconds(toolConfigSse.getRequestTimeout()))
                .build();

        return new McpToolset(sseServer);
    }
}
