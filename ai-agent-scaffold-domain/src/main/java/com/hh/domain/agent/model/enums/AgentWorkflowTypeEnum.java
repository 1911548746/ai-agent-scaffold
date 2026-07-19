package com.hh.domain.agent.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public enum AgentWorkflowTypeEnum {

    Loop("循环执行","loop","loopAgentNode"),
    Parallel("并行执行","parallel","parallelAgentNode"),
    Sequential("串行执行","sequential","sequentialAgentNode"),
    BaseLlm("基础LLM","baseLlm","baseLlmAgentNode"),
    ;

    private String name;
    private String type;
    private String node;

    public static AgentWorkflowTypeEnum getByType(String type) {
        if (type == null) {
            return null;
        }
        for (AgentWorkflowTypeEnum value : values()) {
            if (value.getType().equalsIgnoreCase(type)) {
                return value;
            }
        }
        return null;
    }

}
