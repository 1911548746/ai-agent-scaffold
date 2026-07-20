package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.legacyconfig.ChatModel;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private StreamingChatModelNode streamingChatModelNode;

    @Override
    protected AgentRuntime doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

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
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(chatModelConfig.getBaseUrl())
                .apiKey(chatModelConfig.getApiKey())
                .modelName(chatModelConfig.getModelName())
                .timeout(Duration.ofSeconds(chatModelConfig.getTimeout()))
                .maxRetries(chatModelConfig.getMaxRetries())
                .build();

        dynamicContext.setChatModel(chatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AgentRuntime> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return streamingChatModelNode;
    }

}
