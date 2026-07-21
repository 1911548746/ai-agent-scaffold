package com.hh.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class StreamingChatModelNode extends AbstractArmorySupport {

    @Resource
    private LlmModelNode llmModelNode;

    // TODO 与 ChatModelNode 合并
    @Override
    protected AgentRuntime doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - StreamingChatModelNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        ChatModelDefinition chatModelDefinition = definition.getChatModel();

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
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AgentRuntime> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return llmModelNode;
    }

}
