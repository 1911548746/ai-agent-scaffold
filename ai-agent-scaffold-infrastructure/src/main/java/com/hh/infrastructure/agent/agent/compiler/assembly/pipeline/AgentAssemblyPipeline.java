package com.hh.infrastructure.agent.agent.compiler.assembly.pipeline;

import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
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

    public AdkAgentRuntime compile(AgentDefinition agentDefinition) throws Exception {
        AgentAssemblyPipelineContext context = new AgentAssemblyPipelineContext();
        try {
            return rootNode.apply(
                    new AgentAssemblyCommand(agentDefinition),
                    context
            );
        } catch (RuntimeException | Error error) {
            closeAssemblyResources(context, error);
            throw error;
        } catch (Exception error) {
            closeAssemblyResources(context, error);
            throw error;
        }
    }

    /** 装配失败时回收尚未转移给 Runtime 的 MCP/Plugin 临时资源，不覆盖原始配置异常。 */
    private void closeAssemblyResources(AgentAssemblyPipelineContext context, Throwable original) {
        try {
            context.closeProvisionalResources();
        } catch (RuntimeException cleanupError) {
            original.addSuppressed(cleanupError);
        }
    }

}
