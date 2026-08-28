package com.hh.config.agent.catalog.loader.pipeline;

import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Loader 策略节点的公共父类，统一串行路由和默认结束行为。 */
public abstract class AgentDefinitionLoadNode extends AbstractMultiThreadStrategyRouter<
        AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> {

    @Override
    protected void multiThread(
            AgentDefinitionLoadCommand command,
            AgentDefinitionLoadPipelineContext context)
            throws ExecutionException, InterruptedException, TimeoutException {
    }
}
