package com.hh.domain.agent.model.runtime;

import com.google.adk.runner.Runner;
import com.hh.domain.agent.model.legacyconfig.AgentInfo;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;

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
     * 将旧策略树的装配结果转换为运行时快照；该兼容类型不会再注册为 Spring Bean。
     */
    public static AgentRuntime from(AiAgentRegisterVO registerVO) {
        return new AgentRuntime(
                registerVO.getAppName(),
                registerVO.getAgentId(),
                registerVO.getAgentName(),
                registerVO.getAgentDesc(),
                registerVO.getRunner());
    }

    /**
     * 对外列表接口仍使用历史 DTO，因此每次返回独立副本，避免调用方修改 Registry 元数据。
     */
    public AgentInfo toAgentInfo() {
        AgentInfo agentInfo = new AgentInfo();
        agentInfo.setAgentId(agentId);
        agentInfo.setAgentName(agentName);
        agentInfo.setAgentDesc(agentDesc);
        return agentInfo;
    }
}
