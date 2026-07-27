package com.hh.infrastructure.agent.agent.compiler.assembly.matter.mcp.client;

import com.hh.domain.agent.model.definition.McpToolDefinition;

public interface McpToolCreateService<T> {

    T buildMcpToolset(McpToolDefinition mcpToolConfig);

}
