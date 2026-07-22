package com.hh.infrastructure.agent.runtime.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.AbstractArmorySupport;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RootNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AdkAgentRuntime doApply(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> get(ArmoryCommand armoryCommand, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}
