package com.hh.domain.agent.service.armory.matter.mcp.client;

import com.hh.domain.agent.model.definition.McpToolDefinition;

public interface McpToolCreateService<T> {

    T buildMcpToolset(McpToolDefinition mcpToolConfig);

}
