package com.hh.infrastructure.agent.agent.compiler.assembly.pipeline;

import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.compiler.assembly.node.RootNode;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * ADK Agent 装配策略树的入口。
 */
@Service
public class AgentAssemblyPipeline {

    @Resource
    private RootNode rootNode;

    public AdkAgentRuntime compile(AgentDefinition agentDefinition) throws Exception {
        return rootNode.apply(
                new AgentAssemblyCommand(agentDefinition),
                new AgentAssemblyPipelineContext()
        );
    }

}
