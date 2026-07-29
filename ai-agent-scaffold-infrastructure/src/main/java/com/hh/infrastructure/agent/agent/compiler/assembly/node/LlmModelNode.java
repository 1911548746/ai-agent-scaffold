package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.langchain4j.LangChain4j;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.model.langchain4j.LangChain4jStreamingLlmDecorator;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblySupport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmModelNode extends AgentAssemblySupport {

    @Resource
    private LlmAgentNode llmAgentNode;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LlmModelNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        String modelName = definition.getChatModel().getModelName();

        OpenAiChatModel chatModel = dynamicContext.getChatModel();
        OpenAiStreamingChatModel streamingChatModel = dynamicContext.getStreamingChatModel();

        LangChain4j langChain4j = LangChain4j.builder()
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .modelName(modelName)
                .build();

        // 保留官方请求、工具和同步响应映射，仅修正 1.7.0 流式文本缺失 partial/终态的问题。
        BaseLlm llmModel = new LangChain4jStreamingLlmDecorator(langChain4j);
        dynamicContext.setLlmModel(llmModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> get(AgentAssemblyCommand command, DynamicContext dynamicContext) throws Exception {
        return llmAgentNode;
    }

}
