package com.hh.infrastructure.agent.runtime.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.LlmAgentDefinition;
import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.AbstractArmorySupport;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.McpToolCreateService;
import com.hh.infrastructure.agent.runtime.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.hh.infrastructure.agent.runtime.armory.matter.skills.SkillSourceFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LlmAgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private DefaultMcpClientFactory mcpClientFactory;

    @Resource
    private SkillSourceFactory skillSourceFactory;

    @Override
    protected AdkAgentRuntime doApply(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
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
                    .tools(toolsets)
                    .build();

            dynamicContext.getAgentGroup().put(agentDefinition.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> get(ArmoryCommand armoryCommand, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
