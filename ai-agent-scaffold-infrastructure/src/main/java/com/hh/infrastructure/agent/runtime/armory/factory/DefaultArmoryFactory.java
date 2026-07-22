package com.hh.infrastructure.agent.runtime.armory.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.models.BaseLlm;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.node.RootNode;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class DefaultArmoryFactory {

    @Resource
    private RootNode rootNode;

    public StrategyHandler<ArmoryCommand, DynamicContext, AdkAgentRuntime> armoryStrategyHandler() {
        return rootNode;
    }

    @Data
    public static class  DynamicContext {

        private OpenAiChatModel chatModel;
        private OpenAiStreamingChatModel streamingChatModel;
        private BaseLlm llmModel;
        private Map<String, BaseAgent> agentGroup = new HashMap<>();

        // 配置工作流相关
        // 当前步骤索引
        private AtomicInteger currentStepIndex = new AtomicInteger(0);
        // 当前步骤工作流
        private AgentWorkflowDefinition currentAgentWorkflow;


        public List<BaseAgent> queryAgentList(List<String> agentNames) {

            if (agentNames == null || agentNames.size() == 0) {
                return Collections.emptyList();
            }

            List<BaseAgent> agentList = new ArrayList<>();
            for (String agentName : agentNames) {
                agentList.add(agentGroup.get(agentName));
            }
            return agentList;
        }
    }

}
