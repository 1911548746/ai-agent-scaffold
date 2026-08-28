package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component("agentAssemblyEndNode")
public class EndNode extends AgentAssemblyNode {

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {

        AgentDefinition agentDefinition = requestParameter.getAgentDefinition();
        String agentName = agentDefinition.getAgentDescriptor().getAgentName();
        String agentDesc = agentDefinition.getAgentDescriptor().getAgentDesc();

        log.info("Ai Agent 装配完成 - {} - {}", agentName, agentDesc);

        // 返回临时构建结果；AgentCompiler 在所有 Agent 成功后统一发布 Registry 快照。
        return context.getAgentRuntime();
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return defaultStrategyHandler;
    }

}
