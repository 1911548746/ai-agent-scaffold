package com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.factory;

import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.McpToolCreateService;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.impl.HttpMcpToolCreateService;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.impl.LocalMcpToolCreateService;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.impl.SSEMcpToolCreateService;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.impl.StdioMcpToolCreateService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class DefaultMcpClientFactory {

    @Resource
    private HttpMcpToolCreateService httpMcpToolCreateService;
    @Resource
    private StdioMcpToolCreateService stdioMcpToolCreateService;
    @Resource
    private SSEMcpToolCreateService sseMcpToolCreateService;
    @Resource
    private LocalMcpToolCreateService localMcpToolCreateService;

    public McpToolCreateService getMcpToolCreateService(McpToolDefinition mcpToolConfig) {
        if (null != mcpToolConfig.getSse()) {
            return sseMcpToolCreateService;
        }
        if (null != mcpToolConfig.getHttp()) {
            return httpMcpToolCreateService;
        }
        if (null != mcpToolConfig.getStdio()) {
            return stdioMcpToolCreateService;
        }
        if (null != mcpToolConfig.getLocal()) {
            return localMcpToolCreateService;
        }
        throw new IllegalArgumentException("Invalid mcpToolConfig: " + mcpToolConfig);
    }

}
