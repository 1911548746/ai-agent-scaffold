package com.hh.infrastructure.agent.compiler.assembly.context;

import com.google.adk.agents.BaseAgent;
import com.google.adk.models.BaseLlm;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 装配临时上下文
 */
@Data
public class DynamicContext {

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
