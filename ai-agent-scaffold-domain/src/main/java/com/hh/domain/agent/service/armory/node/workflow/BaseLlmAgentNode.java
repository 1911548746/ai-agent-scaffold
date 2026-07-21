package com.hh.domain.agent.service.armory.node.workflow;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BaseLlmAgentNode extends AbstractArmorySupport {


    @Override
    protected AgentRuntime doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - BaseLlmAgentNode");

        AgentWorkflowDefinition baseLlmAgentDefinition = dynamicContext.getCurrentAgentWorkflow();

        // 查询子智能体列表
        List<BaseAgent> subAgents = dynamicContext.queryAgentList(baseLlmAgentDefinition.getSubAgents());

        LlmAgent llmAgent = LlmAgent.builder()
                .name(baseLlmAgentDefinition.getName())
                .description(baseLlmAgentDefinition.getDescription())
                .instruction(baseLlmAgentDefinition.getInstruction())
                .subAgents(subAgents)
                .build();

        dynamicContext.getAgentGroup().put(baseLlmAgentDefinition.getName(), llmAgent);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AgentRuntime> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("agentWorkflowNode");
    }

}
