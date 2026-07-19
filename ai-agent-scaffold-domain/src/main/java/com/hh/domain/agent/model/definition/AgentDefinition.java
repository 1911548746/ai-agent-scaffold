package com.hh.domain.agent.model.definition;

import com.hh.domain.agent.model.legacyconfig.AgentInfo;
import com.hh.domain.agent.model.legacyconfig.AgentWorkflow;
import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.legacyconfig.ChatModel;
import com.hh.domain.agent.model.legacyconfig.LlmAgent;
import com.hh.domain.agent.model.legacyconfig.Module;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 已完成引用解析和工作流排序的完整 Agent 定义。
 *
 * <p>它是模块化 YAML 与旧版 AiAgentConfigTableVO 之间的过渡对象，避免让现有 Armory
 * 直接感知 catalog、资源路径和稳定引用 ID。</p>
 */
@Getter
@Builder
public class AgentDefinition {

    private final String id;
    private final boolean enabled;
    // 保留来源便于诊断 catalog 条目，运行时装配不依赖该字段。
    private final String sourcePath;
    private final String appName;
    private final AgentInfo agentInfo;
    private final String rootAgentName;
    private final ChatModel chatModel;
    @Builder.Default
    private final List<LlmAgent> agents = List.of();
    @Builder.Default
    private final List<AgentWorkflow> agentWorkflows = List.of();
    private final Module.Runner runner;

    /**
     * 将编译结果适配为旧策略树的输入类型。
     *
     * <p>集合复制后交给旧装配链，避免它与定义对象共享可变 List 实例。</p>
     */
    public AiAgentConfigTableVO toLegacyConfig() {
        Module module = new Module();
        module.setChatModel(chatModel);
        module.setAgents(new ArrayList<>(agents));
        module.setAgentWorkflows(new ArrayList<>(agentWorkflows));
        module.setRunner(runner);

        AiAgentConfigTableVO table = new AiAgentConfigTableVO();
        table.setAppName(appName);
        table.setAgentInfo(agentInfo);
        table.setModule(module);
        return table;
    }
}
