package com.hh.infrastructure.agent.runtime.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.application.agent.port.AgentRuntimeCompiler;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;

@Service
public class ArmoryService implements AgentRuntimeCompiler {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;
    @Resource
    private AdkAgentRuntimeRegistry runtimeRegistry;

    @Override
    public void compileAndPublish(List<AgentDefinition> definitions) throws Exception {

        List<AdkAgentRuntime> runtimes = new ArrayList<>();

        for (AgentDefinition definition : definitions) {
            StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> strategyHandler = defaultArmoryFactory.armoryStrategyHandler();
            // 每个 Agent 使用独立上下文构建；返回值由 AgentCompiler 统一原子发布。
            AdkAgentRuntime runtime = strategyHandler.apply(
                    ArmoryCommand.builder()
                            .agentDefinition(definition)
                            .build(),
                    new DefaultArmoryFactory.DynamicContext()
            );
            runtimes.add(runtime);
        }

        runtimeRegistry.publish(List.copyOf(runtimes));
    }

    @Override
    public void clear() {
        runtimeRegistry.publish(List.of());
    }

}
