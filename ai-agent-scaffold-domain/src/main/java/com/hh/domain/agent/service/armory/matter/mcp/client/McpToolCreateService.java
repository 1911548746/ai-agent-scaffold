package com.hh.domain.agent.service.armory.matter.mcp.client;

import com.hh.domain.agent.model.valobj.McpTool;

public interface McpToolCreateService<T> {

    T buildMcpToolset(McpTool mcpToolConfig);

}
