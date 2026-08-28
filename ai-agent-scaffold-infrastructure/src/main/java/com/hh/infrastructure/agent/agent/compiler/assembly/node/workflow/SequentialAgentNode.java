package com.hh.infrastructure.agent.agent.compiler.assembly.node.workflow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
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
public class SequentialAgentNode extends AgentAssemblyNode {


    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - SequentialAgentNode");

        AgentWorkflowDefinition sequentialAgentDefinition = context.getCurrentAgentWorkflow();

        // 查询子智能体列表
        List<BaseAgent> subAgents = context.queryAgentList(sequentialAgentDefinition.getSubAgents());

        SequentialAgent sequentialAgent = SequentialAgent.builder()
                .name(sequentialAgentDefinition.getName())
                .description(sequentialAgentDefinition.getDescription())
                .subAgents(subAgents)
                .build();

        context.getAgentGroup().put(sequentialAgentDefinition.getName(), sequentialAgent);

        return router(requestParameter, context);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
