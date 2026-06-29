package com.hh.domain.agent.model.valobj;

import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    private String appName;
    private String agentId;
    private String agentName;
    private String agentDesc;
    private Runner runner;

}
