package com.hh.domain.agent.model.definition;

import lombok.Data;

import java.util.List;

/**
 * Runner 运行策略定义，不承载 ADK Runner 实例。
 */
@Data
public class RunnerDefinition {

    private String agentName;
    private Boolean persistence;
    private ContextCompressionDefinition contextCompress;
    private List<String> pluginNameList;
}
