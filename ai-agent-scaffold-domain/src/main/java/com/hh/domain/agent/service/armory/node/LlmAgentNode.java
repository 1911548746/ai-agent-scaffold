package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.legacyconfig.McpTool;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.hh.domain.agent.service.armory.matter.mcp.client.McpToolCreateService;
import com.hh.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.hh.domain.agent.service.armory.matter.skills.SkillSourceFactory;
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
    protected AgentRuntime doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LlmAgentNode");

        BaseLlm llmModel = dynamicContext.getLlmModel();

        AgentDefinition definition = requestParameter.getAgentDefinition();
        List<com.hh.domain.agent.model.legacyconfig.LlmAgent> agentsConfig = definition.getAgents();

        for (com.hh.domain.agent.model.legacyconfig.LlmAgent agentConfig : agentsConfig) {

            List<Object> toolsets = new ArrayList<>();

            // 判断是否配置了mcp工具列表
            if (agentConfig.getMcpToolList() != null && !agentConfig.getMcpToolList().isEmpty()) {
                // 构建mcp服务
                for (McpTool mcpToolConfig : agentConfig.getMcpToolList()) {
                    McpToolCreateService mcpToolCreateService = mcpClientFactory.getMcpToolCreateService(mcpToolConfig);
                    toolsets.add(mcpToolCreateService.buildMcpToolset(mcpToolConfig));
                }
            }

            // 判断是否配置了skills
            if (agentConfig.getSkillsDir() != null) {
                // 构建skills
                SkillSource skillSource = skillSourceFactory.createSkillSource(agentConfig.getSkillsDir());
                toolsets.add(new SkillToolset(skillSource));
            }

            // 构建LlmAgent
            LlmAgent llmAgent = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(llmModel)
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey())
                    .tools(toolsets)
                    .build();

            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AgentRuntime> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
