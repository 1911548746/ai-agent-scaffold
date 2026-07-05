package com.hh.domain.agent.service.armory.matter.mcp.client;

import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;

public interface McpToolCreateService<T> {

    T buildMcpToolset(AiAgentConfigTableVO.McpTool mcpToolConfig);

}
