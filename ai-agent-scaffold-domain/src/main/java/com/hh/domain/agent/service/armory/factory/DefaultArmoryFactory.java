package com.hh.domain.agent.service.armory.factory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.models.BaseLlm;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.legacyconfig.AgentWorkflow;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.service.armory.node.RootNode;
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

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, AiAgentRegisterVO> armoryStrategyHandler() {
        return rootNode;
    }

    @Data
    public static class  DynamicContext {


        // 节点
        private OpenAiChatModel chatModel;

        private OpenAiStreamingChatModel streamingChatModel;

        private BaseLlm llmModel;

        private Map<String, BaseAgent> agentGroup = new HashMap<>();


        /**
         * 原子安全的递进步骤
         */
        private AtomicInteger currentStepIndex = new AtomicInteger(0);

        /**
         * 当前的智能体
         */
        private AgentWorkflow currentAgentWorkflow;


        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }

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
