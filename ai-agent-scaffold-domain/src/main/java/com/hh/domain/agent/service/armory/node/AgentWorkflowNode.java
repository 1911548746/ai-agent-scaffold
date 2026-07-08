package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.valobj.AgentWorkflow;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.model.valobj.enums.AgentTypeEnum;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
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
    private RunnerNode runnerNode;


    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentWorkflowNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AgentWorkflow> agentWorkflowsConfig = aiAgentConfigTableVO.getModule().getAgentWorkflows();

        // 如果未配置 agentWorkflows, 或者当前步骤索引超出 agentWorkflows 范围, 则直接流转到 RunnerNode
        if (null == agentWorkflowsConfig || agentWorkflowsConfig.isEmpty() || dynamicContext.getCurrentStepIndex().get() >= agentWorkflowsConfig.size()) {
            dynamicContext.setCurrentAgentWorkflow(null);
            return router(requestParameter, dynamicContext);
        }

        // 设置当前步骤的 agentWorkflow
        dynamicContext.setCurrentAgentWorkflow(agentWorkflowsConfig.get(dynamicContext.getCurrentStepIndex().get()));
        // 增加当前步骤索引
        dynamicContext.getCurrentStepIndex().incrementAndGet();

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {

        AgentWorkflow currentAgentWorkflow = dynamicContext.getCurrentAgentWorkflow();

        if (null == currentAgentWorkflow) {
            // 如果未配置 agentWorkflows 则直接流转到 RunnerNode
            return runnerNode;
        }

        String type = currentAgentWorkflow.getType();
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.getByType(type);

        if (null == agentTypeEnum) {
            throw new RuntimeException("agentWorkflow type is error!");
        }

        return switch (agentTypeEnum) {
            case Loop -> loopAgentNode;
            case Parallel -> parallelAgentNode;
            case Sequential -> sequentialAgentNode;
            default -> runnerNode;
        };
    }

}
