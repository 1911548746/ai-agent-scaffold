package com.hh.domain.agent.service.armory.matter.mcp.client.impl;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioServerParameters;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.service.armory.matter.mcp.client.McpToolCreateService;
import org.springframework.stereotype.Service;

@Service
public class StdioMcpToolCreateService implements McpToolCreateService<McpToolset> {
    @Override
    public McpToolset buildMcpToolset(AiAgentConfigTableVO.McpTool mcpToolConfig) {

        AiAgentConfigTableVO.McpTool.StdioServerParameters toolConfigStdio = mcpToolConfig.getStdio();

        StdioServerParameters stdioServer = StdioServerParameters.builder()
                .command(toolConfigStdio.getCommand())
                .args(toolConfigStdio.getArgs())
                .env(toolConfigStdio.getEnv())
                .build();

        return new McpToolset(stdioServer.toServerParameters());
    }
}
