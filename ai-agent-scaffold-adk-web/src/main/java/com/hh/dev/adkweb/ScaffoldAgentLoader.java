package com.hh.dev.adkweb;

import com.google.adk.agents.BaseAgent;
import com.google.adk.web.AgentLoader;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 将项目已经发布的 ADK Runtime 快照转换为 ADK Web 可读取的 Agent 列表。
 *
 * <p>Loader 只在启动时复制一次 Registry 快照，不监听后续变化。这样 ADK Web 的
 * Agent 列表与本次调试进程使用的对象保持一致，也不会引入尚未设计的热更新语义。</p>
 */
public final class ScaffoldAgentLoader implements AgentLoader {

    private final ImmutableMap<String, BaseAgent> agentsByAppName;

    public ScaffoldAgentLoader(Collection<AdkAgentRuntime> runtimes) {
        Objects.requireNonNull(runtimes, "runtimes must not be null");

        Map<String, BaseAgent> agents = new LinkedHashMap<>();
        for (AdkAgentRuntime runtime : runtimes) {
            AdkAgentRuntime nonNullRuntime = Objects.requireNonNull(
                    runtime, "runtime must not be null");
            String appName = nonNullRuntime.appName();
            BaseAgent rootAgent = Objects.requireNonNull(
                    nonNullRuntime.runner().agent(),
                    "root Agent must not be null for appName '" + appName + "'");

            // ADK Web 使用 list-apps 返回值作为 Runner 的 appName，因此必须以项目 appName 建键。
            if (agents.putIfAbsent(appName, rootAgent) != null) {
                throw new IllegalStateException(
                        "ADK Web cannot start: duplicate appName '" + appName + "'");
            }
        }

        if (agents.isEmpty()) {
            throw new IllegalStateException(
                    "ADK Web cannot start: Agent Runtime Registry is empty");
        }
        this.agentsByAppName = ImmutableMap.copyOf(agents);
    }

    /** 返回本次启动时固定的应用名称，避免运行期间列表发生漂移。 */
    @Override
    @Nonnull
    public ImmutableList<String> listAgents() {
        return agentsByAppName.keySet().asList();
    }

    /** 按项目 appName 返回已经装配好的根 Agent。 */
    @Override
    public BaseAgent loadAgent(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent appName must not be blank");
        }
        BaseAgent agent = agentsByAppName.get(name);
        if (agent == null) {
            throw new NoSuchElementException("Agent appName not found: " + name);
        }
        return agent;
    }
}
