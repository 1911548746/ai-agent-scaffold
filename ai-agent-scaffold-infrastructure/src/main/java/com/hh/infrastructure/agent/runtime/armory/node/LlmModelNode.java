package com.hh.infrastructure.agent.runtime.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.models.langchain4j.LangChain4j;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.AbstractArmorySupport;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LlmModelNode extends AbstractArmorySupport {

    @Resource
    private LlmAgentNode llmAgentNode;

    @Override
    protected AdkAgentRuntime doApply(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - LlmModelNode");

        AgentDefinition definition = requestParameter.getAgentDefinition();
        String modelName = definition.getChatModel().getModelName();

        OpenAiChatModel chatModel = dynamicContext.getChatModel();
        OpenAiStreamingChatModel streamingChatModel = dynamicContext.getStreamingChatModel();

        LangChain4j llmModel = LangChain4j.builder()
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .modelName(modelName)
                .build();

        dynamicContext.setLlmModel(llmModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> get(ArmoryCommand armoryCommand, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return llmAgentNode;
    }

}
