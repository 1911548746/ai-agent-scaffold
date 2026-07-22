package com.hh.infrastructure.agent.runtime.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.domain.agent.model.enums.AgentWorkflowTypeEnum;
import com.hh.infrastructure.agent.runtime.armory.AbstractArmorySupport;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import com.hh.infrastructure.agent.runtime.armory.node.workflow.BaseLlmAgentNode;
import com.hh.infrastructure.agent.runtime.armory.node.workflow.LoopAgentNode;
import com.hh.infrastructure.agent.runtime.armory.node.workflow.ParallelAgentNode;
import com.hh.infrastructure.agent.runtime.armory.node.workflow.SequentialAgentNode;
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
    protected AdkAgentRuntime doApply(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        List<AgentWorkflowDefinition> agentWorkflowDefinitionList = definition.getAgentWorkflows();

        // 如果未配置 agentWorkflows, 或者当前步骤索引超出 agentWorkflows 的 size , 则直接结束, 流转到 RunnerNode
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
    public StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> get(ArmoryCommand armoryCommand, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

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
