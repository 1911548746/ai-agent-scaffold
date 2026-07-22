package com.hh.infrastructure.agent.compiler.assembly.matter.mcp.client;

import com.hh.domain.agent.model.definition.McpToolDefinition;

public interface McpToolCreateService<T> {

    T buildMcpToolset(McpToolDefinition mcpToolConfig);

}
