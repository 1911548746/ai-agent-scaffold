package com.hh.domain.agent.model.definition;

import lombok.Data;

import java.util.List;

/**
 * 已完成拓扑排序的工作流节点定义；subAgents 使用可直接装配的名称而非 YAML 引用 ID。
 */
@Data
public class AgentWorkflowDefinition {

    /**
     * 工作流类型：loop、parallel、sequential 以及 baseLlm
     */
    private String type;
    private String name;
    private String description;
    private String instruction;
    private List<String> subAgents;
    private Integer maxIterations = 3;
}
