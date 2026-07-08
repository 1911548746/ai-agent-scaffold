package com.hh.domain.agent.service.armory.matter.mcp.client.factory;

import com.hh.domain.agent.model.valobj.McpTool;
import com.hh.domain.agent.service.armory.matter.mcp.client.McpToolCreateService;
import com.hh.domain.agent.service.armory.matter.mcp.client.impl.HttpMcpToolCreateService;
import com.hh.domain.agent.service.armory.matter.mcp.client.impl.LocalMcpToolCreateService;
import com.hh.domain.agent.service.armory.matter.mcp.client.impl.SSEMcpToolCreateService;
import com.hh.domain.agent.service.armory.matter.mcp.client.impl.StdioMcpToolCreateService;
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

    public McpToolCreateService getMcpToolCreateService(McpTool mcpToolConfig) {
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
