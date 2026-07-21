package com.hh.domain.agent.model.definition;

import lombok.Data;

import java.util.List;

/**
 * 一个 LLM Agent 节点的已解析定义。
 */
@Data
public class LlmAgentDefinition {

    private String name;
    private String description;
    private String instruction;
    private String outputKey;
    private List<McpToolDefinition> mcpToolList;
    private String skillsDir;
}
