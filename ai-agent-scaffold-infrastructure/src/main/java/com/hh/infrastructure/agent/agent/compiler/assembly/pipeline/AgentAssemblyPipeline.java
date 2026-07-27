package com.hh.infrastructure.agent.agent.compiler.assembly.pipeline;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.compiler.assembly.node.RootNode;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

/**
 * ADK Agent 装配策略树的入口。
 */
@Service
public class AgentAssemblyPipeline {

    @Resource
    private RootNode rootNode;

    public StrategyHandler<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> strategyHandler() {
        return rootNode;
    }

}
