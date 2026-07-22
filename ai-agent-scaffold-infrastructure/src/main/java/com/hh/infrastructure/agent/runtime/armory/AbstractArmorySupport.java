package com.hh.infrastructure.agent.runtime.armory;


import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Slf4j
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> {

    @Resource
    protected ApplicationContext applicationContext;

    /**
     * 通用的Bean获取方法
     */
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    @Override
    protected void multiThread(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

}
