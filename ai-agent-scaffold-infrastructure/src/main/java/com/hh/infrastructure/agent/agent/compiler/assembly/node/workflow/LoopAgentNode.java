package com.hh.infrastructure.agent.agent.compiler.assembly.node.workflow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class LoopAgentNode extends AgentAssemblyNode {


    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        AgentWorkflowDefinition loopAgentDefinition = context.getCurrentAgentWorkflow();

        // 查询子智能体列表
        List<BaseAgent> subAgents = context.queryAgentList(loopAgentDefinition.getSubAgents());

        LoopAgent loopAgent = LoopAgent.builder()
                .name(loopAgentDefinition.getName())
                .description(loopAgentDefinition.getDescription())
                .subAgents(subAgents)
                .maxIterations(loopAgentDefinition.getMaxIterations())
                .build();

        context.getAgentGroup().put(loopAgentDefinition.getName(), loopAgent);

        return router(requestParameter, context);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
