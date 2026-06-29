package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.runner.InMemoryRunner;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RunnerNode  extends AbstractArmorySupport {

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();
        String agentId = aiAgentConfigTableVO.getAgentInfo().getAgentId();
        String agentName = aiAgentConfigTableVO.getAgentInfo().getAgentName();
        String agentDesc = aiAgentConfigTableVO.getAgentInfo().getAgentDesc();

        InMemoryRunner runner = createRunner(requestParameter, dynamicContext, appName);

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .build();

        // 注册到Spring容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // TODO 持久化 需要配置APP
    private InMemoryRunner createRunner(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext, String appName) {
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String agentName = aiAgentConfigTableVO.getModule().getRunner().getAgentName();

        // 获取智能体（用这个智能体装配 InMemoryRunner）
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(agentName);

        // 会话运行节点
        return new InMemoryRunner(baseAgent, appName);
    }

}
