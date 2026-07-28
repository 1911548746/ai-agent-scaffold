package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;

import java.util.Objects;

/**
 * 已完成 ADK 装配的基础设施运行时快照。
 *
 * <p>该类型刻意留在 infrastructure：Runner 是 Google ADK 的 SDK 类型，不能再作为
 * domain 模型的一部分。请求侧只能通过运行时端口访问此快照。</p>
 */
public record AdkAgentRuntime(
        String appName,
        String agentId,
        String agentName,
        String agentDesc,
        Runner runner,
        RunConfig runConfig,
        RuntimeCompactionPlan compactionPlan) {

    public AdkAgentRuntime(
            String appName,
            String agentId,
            String agentName,
            String agentDesc,
            Runner runner,
            RunConfig runConfig) {
        this(appName, agentId, agentName, agentDesc, runner, runConfig,
                RuntimeCompactionPlan.disabled());
    }

    public AdkAgentRuntime {
        Objects.requireNonNull(appName, "appName must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(agentDesc, "agentDesc must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(runConfig, "runConfig must not be null");
        Objects.requireNonNull(compactionPlan, "compactionPlan must not be null");
    }

    /**
     * 对列表查询只暴露领域展示元数据，避免 ADK Runner 泄漏到上层。
     */
    public AgentDescriptor toAgentDescriptor() {
        AgentDescriptor descriptor = new AgentDescriptor();
        descriptor.setAgentId(agentId);
        descriptor.setAgentName(agentName);
        descriptor.setAgentDesc(agentDesc);
        return descriptor;
    }
}
