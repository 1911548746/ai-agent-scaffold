package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.LoadArtifactsTool;
import com.google.adk.tools.skills.SkillToolset;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.LlmAgentDefinition;
import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
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
public class LlmAgentNode extends AgentAssemblyNode {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private DefaultMcpClientFactory mcpClientFactory;

    @Resource
    private LocalToolCreateService localToolCreateService;

    @Resource
    private SkillSourceFactory skillSourceFactory;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - LlmAgentNode");

        BaseLlm llmModel = context.getLlmModel();

        AgentDefinition definition = requestParameter.getAgentDefinition();
        List<LlmAgentDefinition> agentDefinitionList = definition.getAgents();

        for (LlmAgentDefinition agentDefinition : agentDefinitionList) {

            List<Object> toolsets = new ArrayList<>();

            // 内置工具
            /**
             * LoadArtifactsTool
             * <p>该工具只在 LLM 请求构造阶段临时加载当前 Session 的 Artifact，不会把原始字节写回 Event</p>
             * <p>无可用 Artifact 时它只返回空列表</p>
             */
            toolsets.add(LoadArtifactsTool.INSTANCE);

            // 判断是否配置了mcp工具列表
            if (agentDefinition.getMcpToolList() != null && !agentDefinition.getMcpToolList().isEmpty()) {
                // 构建mcp服务
                for (McpToolDefinition mcpToolConfig : agentDefinition.getMcpToolList()) {
                    McpToolCreateService mcpToolCreateService = mcpClientFactory.getMcpToolCreateService(mcpToolConfig);
                    Object toolset = mcpToolCreateService.buildMcpToolset(mcpToolConfig);
                    toolsets.add(toolset);
                    if (toolset instanceof AutoCloseable closeable) {
                        // LlmAgent 构建失败时还没有 Runner 可负责关闭，先交给装配上下文托管。
                        context.registerProvisionalResource(closeable);
                    }
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

            context.getAgentGroup().put(agentDefinition.getName(), llmAgent);
        }

        return router(requestParameter, context);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return agentWorkflowNode;
    }

}
