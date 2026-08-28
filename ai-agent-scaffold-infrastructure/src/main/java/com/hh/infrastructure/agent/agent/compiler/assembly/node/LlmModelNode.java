package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.langchain4j.LangChain4j;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.model.langchain4j.LangChain4jStreamingLlmDecorator;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class LlmModelNode extends AgentAssemblyNode {

    @Resource
    private LlmAgentNode llmAgentNode;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - LlmModelNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        String modelName = definition.getChatModel().getModelName();

        OpenAiChatModel chatModel = context.getChatModel();
        OpenAiStreamingChatModel streamingChatModel = context.getStreamingChatModel();

        LangChain4j langChain4j = LangChain4j.builder()
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .modelName(modelName)
                .build();

        // 装饰器同时修正 1.7.0 的流式边界，并只在首响应前执行有限重试。
        BaseLlm llmModel = new LangChain4jStreamingLlmDecorator(
                langChain4j,
                Duration.ofSeconds(definition.getChatModel().getStreamingFirstResponseTimeout()),
                definition.getChatModel().getStreamingMaxRetries());
        context.setLlmModel(llmModel);

        return router(requestParameter, context);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return llmAgentNode;
    }

}
