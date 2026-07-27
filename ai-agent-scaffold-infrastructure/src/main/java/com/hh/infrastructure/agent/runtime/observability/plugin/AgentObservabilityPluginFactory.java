package com.hh.infrastructure.agent.runtime.observability.plugin;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 为每个已编译 Runtime 创建独立的 ADK 系统观测 Plugin。
 *
 * <p>Plugin 内部保存尚未结束的模型、工具和子 Agent 计时状态，因此不能作为跨 Runtime
 * 共享的 Spring 单例使用。工厂只共享线程安全的 {@link MeterRegistry}。</p>
 */
@Component
public class AgentObservabilityPluginFactory {

    private final MeterRegistry meterRegistry;

    public AgentObservabilityPluginFactory(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public AgentObservabilityPlugin create(String agentId) {
        return new AgentObservabilityPlugin(agentId, meterRegistry);
    }
}
