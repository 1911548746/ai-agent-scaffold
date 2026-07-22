package com.hh.infrastructure.agent.runtime.armory.node.workflow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LoopAgent;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.AbstractArmorySupport;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class LoopAgentNode extends AbstractArmorySupport {


    @Override
    protected AdkAgentRuntime doApply(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LoopAgentNode");

        AgentWorkflowDefinition loopAgentDefinition = dynamicContext.getCurrentAgentWorkflow();

        // 查询子智能体列表
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(loopAgentDefinition.getSubAgents());

        LoopAgent loopAgent = LoopAgent.builder()
                .name(loopAgentDefinition.getName())
                .description(loopAgentDefinition.getDescription())
                .subAgents(subAgents)
                .maxIterations(loopAgentDefinition.getMaxIterations())
                .build();

        dynamicContext.getAgentGroup().put(loopAgentDefinition.getName(), loopAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> get(ArmoryCommand armoryCommand, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
