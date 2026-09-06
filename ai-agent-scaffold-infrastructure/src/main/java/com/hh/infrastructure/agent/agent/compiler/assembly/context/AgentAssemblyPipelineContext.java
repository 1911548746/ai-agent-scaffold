package com.hh.infrastructure.agent.agent.compiler.assembly.context;

import com.google.adk.agents.BaseAgent;
import com.google.adk.models.BaseLlm;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Data;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 装配临时上下文
 */
@Data
public class AgentAssemblyPipelineContext {

    private OpenAiChatModel chatModel;
    private OpenAiStreamingChatModel streamingChatModel;
    private BaseLlm llmModel;
    private Map<String, BaseAgent> agentGroup = new HashMap<>();

    // 配置工作流相关
    // 当前步骤索引
    private AtomicInteger currentStepIndex = new AtomicInteger(0);
    // 当前步骤工作流
    private AgentWorkflowDefinition currentAgentWorkflow;

    private AdkAgentRuntime agentRuntime;

    /**
     * 装配尚未交给 Runtime 的临时资源。Pipeline 中途失败时由入口统一关闭，避免 MCP
     * Toolset 已创建但 Agent 尚未完成时遗失所有权。
     */
    private final List<AutoCloseable> provisionalResources = new ArrayList<>();


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

    /** 登记一个仍由装配上下文临时持有的资源。 */
    public void registerProvisionalResource(AutoCloseable resource) {
        if (resource != null) {
            provisionalResources.add(resource);
        }
    }

    /** Runtime 接管资源后清空登记表，避免 Pipeline 失败补偿与 Runtime 重复关闭。 */
    public void disarmProvisionalResources() {
        provisionalResources.clear();
    }

    /** 仅在装配失败时关闭仍未转移的临时资源。 */
    public void closeProvisionalResources() {
        List<AutoCloseable> resources = new ArrayList<>(provisionalResources);
        provisionalResources.clear();
        Collections.reverse(resources);
        RuntimeException firstFailure = null;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception error) {
                if (firstFailure == null) {
                    firstFailure = new IllegalStateException("failed to close assembly resource", error);
                } else {
                    firstFailure.addSuppressed(error);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }
}
