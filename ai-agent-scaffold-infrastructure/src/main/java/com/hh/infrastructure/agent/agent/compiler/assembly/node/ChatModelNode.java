package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class ChatModelNode extends AgentAssemblyNode {

    @Resource
    private LlmModelNode llmModelNode;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        ChatModelDefinition chatModelDefinition = definition.getChatModel();

        // 同步模型由 LangChain4j 在超时、限流和 5xx 等可重试错误上执行指数退避；
        // maxRetries 表示首次调用失败后的追加重试次数，而不是总调用次数。
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(chatModelDefinition.getBaseUrl())
                .apiKey(chatModelDefinition.getApiKey())
                .modelName(chatModelDefinition.getModelName())
                .timeout(Duration.ofSeconds(chatModelDefinition.getTimeout()))
                .maxRetries(chatModelDefinition.getMaxRetries())
                .build();
        context.setChatModel(chatModel);

        // 流式响应可能已经向客户端发送部分内容，透明重试会造成重复片段，因此不配置自动重试。
        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(chatModelDefinition.getBaseUrl())
                .apiKey(chatModelDefinition.getApiKey())
                .modelName(chatModelDefinition.getModelName())
                .timeout(Duration.ofSeconds(chatModelDefinition.getTimeout()))
                .build();

        context.setStreamingChatModel(streamingChatModel);

        return router(requestParameter, context);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return llmModelNode;
    }

}
