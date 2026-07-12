package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;
import com.google.adk.skills.LocalSkillSource;
import com.google.adk.skills.SkillSource;
import com.google.adk.tools.skills.SkillToolset;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.model.valobj.McpTool;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.hh.domain.agent.service.armory.matter.mcp.client.McpToolCreateService;
import com.hh.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import com.hh.domain.agent.service.armory.matter.skills.SkillSourceFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LlmAgentNode");

        BaseLlm llmModel = dynamicContext.getLlmModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<com.hh.domain.agent.model.valobj.LlmAgent> agentsConfig = aiAgentConfigTableVO.getModule().getAgents();


        for (com.hh.domain.agent.model.valobj.LlmAgent agentConfig : agentsConfig) {

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
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
