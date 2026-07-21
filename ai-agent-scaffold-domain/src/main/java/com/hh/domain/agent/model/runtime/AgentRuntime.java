package com.hh.domain.agent.model.runtime;

import com.google.adk.runner.Runner;

import java.util.Objects;

/**
 * 一个已完成 ADK 装配、可被请求安全读取的 Agent 运行时快照。
 *
 * <p>record 保证运行时元数据引用不可重新赋值；Runner 是 ADK 的执行入口，必须只在
 * {@link AgentRuntimeRegistry} 的完整快照发布后才对请求可见。</p>
 */
public record AgentRuntime(
        String appName,
        String agentId,
        String agentName,
        String agentDesc,
        Runner runner) {

    public AgentRuntime {
        Objects.requireNonNull(appName, "appName must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(agentDesc, "agentDesc must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
    }

    /**
     * 对外列表接口返回独立展示副本，避免调用方修改 Registry 元数据。
     */
    public AgentDescriptor toAgentDescriptor() {
        AgentDescriptor descriptor = new AgentDescriptor();
        descriptor.setAgentId(agentId);
        descriptor.setAgentName(agentName);
        descriptor.setAgentDesc(agentDesc);
        return descriptor;
    }
}
