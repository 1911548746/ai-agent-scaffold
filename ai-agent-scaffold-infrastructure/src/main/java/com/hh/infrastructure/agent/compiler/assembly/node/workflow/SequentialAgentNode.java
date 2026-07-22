package com.hh.infrastructure.agent.compiler.assembly.node.workflow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.SequentialAgent;
import com.hh.infrastructure.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.infrastructure.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.compiler.assembly.pipeline.AgentAssemblySupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class SequentialAgentNode extends AgentAssemblySupport {


    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - SequentialAgentNode");

        AgentWorkflowDefinition sequentialAgentDefinition = dynamicContext.getCurrentAgentWorkflow();

        // 查询子智能体列表
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(sequentialAgentDefinition.getSubAgents());

        SequentialAgent sequentialAgent = SequentialAgent.builder()
                .name(sequentialAgentDefinition.getName())
                .description(sequentialAgentDefinition.getDescription())
                .subAgents(subAgents)
                .build();

        dynamicContext.getAgentGroup().put(sequentialAgentDefinition.getName(), sequentialAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> get(AgentAssemblyCommand command, DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
