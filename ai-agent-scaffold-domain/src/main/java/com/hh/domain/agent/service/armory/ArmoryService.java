package com.hh.domain.agent.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.service.IArmoryService;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    public List<AgentRuntime> acceptArmoryAgents(List<AgentDefinition> definitions) throws Exception {

        List<AgentRuntime> runtimes = new ArrayList<>();

        for (AgentDefinition definition : definitions) {
            StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AgentRuntime> strategyHandler = defaultArmoryFactory.armoryStrategyHandler();
            // 每个 Agent 使用独立上下文构建；返回值由 AgentCompiler 统一原子发布。
            AgentRuntime runtime = strategyHandler.apply(
                    ArmoryCommandEntity.builder()
                            .agentDefinition(definition)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext()
            );
            runtimes.add(runtime);
        }

        return List.copyOf(runtimes);
    }

}
