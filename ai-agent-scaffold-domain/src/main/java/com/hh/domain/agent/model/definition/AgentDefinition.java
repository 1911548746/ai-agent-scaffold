package com.hh.domain.agent.model.definition;

import com.hh.domain.agent.model.legacyconfig.AgentInfo;
import com.hh.domain.agent.model.legacyconfig.AgentWorkflow;
import com.hh.domain.agent.model.legacyconfig.ChatModel;
import com.hh.domain.agent.model.legacyconfig.LlmAgent;
import com.hh.domain.agent.model.legacyconfig.Module;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 已完成引用解析和工作流排序的完整 Agent 定义。
 *
 * <p>它隔离 catalog、资源路径和稳定引用 ID 等配置细节；Armory 只接收此完成校验的定义，
 * 不再依赖旧版 YAML 表结构。</p>
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

}
