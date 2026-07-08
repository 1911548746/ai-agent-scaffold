package com.hh.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;

@Data
public class LlmAgent {
    private String name;
    private String description;
    private String instruction;
    private String outputKey;
    private List<McpTool> mcpToolList;
}
