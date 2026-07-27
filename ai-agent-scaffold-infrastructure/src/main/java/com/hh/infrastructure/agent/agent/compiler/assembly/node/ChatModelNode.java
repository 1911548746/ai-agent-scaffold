package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblySupport;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class ChatModelNode extends AgentAssemblySupport {

    @Resource
    private LlmModelNode llmModelNode;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        ChatModelDefinition chatModelDefinition = definition.getChatModel();

        // TODO 超时重试优化
        /*
         * dev.langchain4j.exception.TimeoutException: request timed out
         * 	at dev.langchain4j.http.client.jdk.JdkHttpClient.execute(JdkHttpClient.java:59)
         *
         * 	Caused by: java.net.http.HttpTimeoutException: request timed out
         * 	at java.net.http/jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:572)
         * 	at java.net.http/jdk.internal.net.http.HttpClientFacade.send(HttpClientFacade.java:123)
         * 	at dev.langchain4j.http.client.jdk.JdkHttpClient.execute(JdkHttpClient.java:51)
         */

        // ChatModel
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(chatModelDefinition.getBaseUrl())
                .apiKey(chatModelDefinition.getApiKey())
                .modelName(chatModelDefinition.getModelName())
                .timeout(Duration.ofSeconds(chatModelDefinition.getTimeout()))
                .maxRetries(chatModelDefinition.getMaxRetries())
                .build();
        dynamicContext.setChatModel(chatModel);

        // StreamingChatModel
        OpenAiStreamingChatModel streamingChatModel = OpenAiStreamingChatModel.builder()
                .baseUrl(chatModelDefinition.getBaseUrl())
                .apiKey(chatModelDefinition.getApiKey())
                .modelName(chatModelDefinition.getModelName())
                .timeout(Duration.ofSeconds(chatModelDefinition.getTimeout()))
                .build();

        dynamicContext.setStreamingChatModel(streamingChatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> get(AgentAssemblyCommand command, DynamicContext dynamicContext) throws Exception {
        return llmModelNode;
    }

}
