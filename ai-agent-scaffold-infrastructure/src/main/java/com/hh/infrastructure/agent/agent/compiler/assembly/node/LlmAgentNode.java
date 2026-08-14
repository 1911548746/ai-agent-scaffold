package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.LlmAgentDefinition;
import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblySupport;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.mcp.client.McpToolCreateService;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.tool.LocalToolCreateService;
import com.hh.infrastructure.agent.agent.compiler.assembly.matter.skill.SkillSourceFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LlmAgentNode extends AgentAssemblySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private DefaultMcpClientFactory mcpClientFactory;

    @Resource
    private LocalToolCreateService localToolCreateService;

    @Resource
    private SkillSourceFactory skillSourceFactory;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LlmAgentNode");

        BaseLlm llmModel = dynamicContext.getLlmModel();

        AgentDefinition definition = requestParameter.getAgentDefinition();
        List<LlmAgentDefinition> agentDefinitionList = definition.getAgents();

        for (LlmAgentDefinition agentDefinition : agentDefinitionList) {

            List<Object> toolsets = new ArrayList<>();

            // 判断是否配置了mcp工具列表
            if (agentDefinition.getMcpToolList() != null && !agentDefinition.getMcpToolList().isEmpty()) {
                // 构建mcp服务
                for (McpToolDefinition mcpToolConfig : agentDefinition.getMcpToolList()) {
                    McpToolCreateService mcpToolCreateService = mcpClientFactory.getMcpToolCreateService(mcpToolConfig);
                    toolsets.add(mcpToolCreateService.buildMcpToolset(mcpToolConfig));
                }
            }

            // 本地 tool
            if (agentDefinition.getLocalToolList() != null && !agentDefinition.getLocalToolList().isEmpty()) {
                agentDefinition.getLocalToolList().forEach(localTool ->
                        toolsets.add(localToolCreateService.buildTool(localTool)));
            }

            // 判断是否配置了skills
            if (agentDefinition.getSkillsDir() != null) {
                // 构建skills
                SkillSource skillSource = skillSourceFactory.createSkillSource(agentDefinition.getSkillsDir());
                toolsets.add(new SkillToolset(skillSource));
            }

            // 构建LlmAgent
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentDefinition.getName())
                    .description(agentDefinition.getDescription())
                    .model(llmModel)
                    .instruction(agentDefinition.getInstruction())
                    .outputKey(agentDefinition.getOutputKey())
                    .maxSteps(agentDefinition.getMaxSteps())
                    .tools(toolsets)
                    .build();

            dynamicContext.getAgentGroup().put(agentDefinition.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> get(AgentAssemblyCommand command, DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
