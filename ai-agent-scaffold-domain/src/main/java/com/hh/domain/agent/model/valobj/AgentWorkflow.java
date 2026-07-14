package com.hh.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;

@Data
public class AgentWorkflow {
    /**
     * 类型；loop、parallel、sequential
     */
    private String type;
    private String name;
    private String description;
    private String instruction;
    private List<String> subAgents;
    private Integer maxIterations = 3;

}
