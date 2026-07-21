package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.enums.AgentWorkflowTypeEnum;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.hh.domain.agent.service.armory.node.workflow.BaseLlmAgentNode;
import com.hh.domain.agent.service.armory.node.workflow.LoopAgentNode;
import com.hh.domain.agent.service.armory.node.workflow.ParallelAgentNode;
import com.hh.domain.agent.service.armory.node.workflow.SequentialAgentNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class AgentWorkflowNode extends AbstractArmorySupport {

    @Resource
    private LoopAgentNode loopAgentNode;
    @Resource
    private ParallelAgentNode parallelAgentNode;
    @Resource
    private SequentialAgentNode sequentialAgentNode;
    @Resource
    private BaseLlmAgentNode baseLlmAgentNode;

    @Resource
    private RunnerNode runnerNode;


    @Override
    protected AgentRuntime doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        List<AgentWorkflowDefinition> agentWorkflowDefinitionList = definition.getAgentWorkflows();

        // 如果未配置 agentWorkflows, 或者当前步骤索引超出 agentWorkflows 范围, 则直接流转到 RunnerNode
        if (null == agentWorkflowDefinitionList || agentWorkflowDefinitionList.isEmpty() || dynamicContext.getCurrentStepIndex().get() >= agentWorkflowDefinitionList.size()) {
            dynamicContext.setCurrentAgentWorkflow(null);
            return router(requestParameter, dynamicContext);
        }

        // 设置当前步骤的 agentWorkflow
        dynamicContext.setCurrentAgentWorkflow(agentWorkflowDefinitionList.get(dynamicContext.getCurrentStepIndex().get()));
        // 增加当前步骤索引
        dynamicContext.getCurrentStepIndex().incrementAndGet();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AgentRuntime> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AgentWorkflowDefinition currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow) {
            // 如果未配置 agentWorkflows 则直接流转到 RunnerNode
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentWorkflowTypeEnum agentWorkflowTypeEnum = AgentWorkflowTypeEnum.getByType(type);

        if (null == agentWorkflowTypeEnum) {
            throw new RuntimeException("agentWorkflow type is error!");
        }

        return switch (agentWorkflowTypeEnum) {
            case Loop -> loopAgentNode;
            case Parallel -> parallelAgentNode;
            case Sequential -> sequentialAgentNode;
            case BaseLlm -> baseLlmAgentNode;
            default -> runnerNode;
        };
    }

}
