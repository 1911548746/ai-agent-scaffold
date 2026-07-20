package com.hh.domain.agent.service.armory;


import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Slf4j
public abstract class AbstractArmorySupport extends AbstractMultiThreadStrategyRouter<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> {

    @Resource
    protected ApplicationContext applicationContext;

    /**
     * 通用的Bean获取方法
     */
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    @Override
    protected void multiThread(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

}
