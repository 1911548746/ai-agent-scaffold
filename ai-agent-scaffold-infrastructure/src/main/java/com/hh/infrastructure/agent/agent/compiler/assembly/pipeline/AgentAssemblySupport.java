package com.hh.infrastructure.agent.agent.compiler.assembly.pipeline;


import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 装配策略节点的公共父类，统一约束节点只能操作本次编译的临时上下文。
 */
@Slf4j
public abstract class AgentAssemblySupport extends AbstractMultiThreadStrategyRouter<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> {

    @Resource
    protected ApplicationContext applicationContext;

    /**
     * 通用的Bean获取方法
     */
    protected <T> T getBean(String beanName) {
        return (T) applicationContext.getBean(beanName);
    }

    @Override
    protected void multiThread(AgentAssemblyCommand requestParameter, DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

}
