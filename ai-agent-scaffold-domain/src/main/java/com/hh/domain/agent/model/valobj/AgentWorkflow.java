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
    private List<String> subAgents;
    private String description;
    private Integer maxIterations = 3;

}
