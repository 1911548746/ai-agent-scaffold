package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.agents.BaseAgent;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 Runtime 的资源所有者。
 *
 * <p>ADK 的 Agent.close 会递归关闭 Agent 树中的 Toolset，因此 MCP 客户端和 stdio 子进程
 * 由这里间接回收。Spring 管理的业务 Plugin、SessionStore 和共享 MinIO 客户端不纳入本作用域。</p>
 */
@Slf4j
public final class RuntimeResourceScope implements AutoCloseable {

    private final Runner runner;
    private final List<? extends Plugin> ownedPlugins;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private RuntimeResourceScope(Runner runner, Collection<? extends Plugin> ownedPlugins) {
        this.runner = runner;
        this.ownedPlugins = List.copyOf(ownedPlugins);
    }

    /** 创建由当前 Runtime 独占的资源作用域。 */
    public static RuntimeResourceScope of(
            Runner runner, Collection<? extends Plugin> ownedPlugins) {
        return new RuntimeResourceScope(runner, ownedPlugins);
    }

    /** 测试或兼容旧构造器使用的空作用域，不拥有任何 Runner 资源。 */
    public static RuntimeResourceScope noop() {
        return new RuntimeResourceScope(null, List.of());
    }

    /** 幂等关闭 Runtime 自有资源；单个资源失败不会阻断其他资源回收。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        // 不调用 Runner.close：它还会关闭由 Spring 共享的业务 Plugin。
        closeAgentTree();
        for (Plugin plugin : ownedPlugins) {
            try {
                plugin.close().blockingAwait();
            } catch (Throwable error) {
                log.warn("关闭 Runtime 自有 Plugin 失败, pluginType={}, errorType={}",
                        plugin.getClass().getName(), error.getClass().getSimpleName());
            }
        }
    }

    private void closeAgentTree() {
        if (runner == null) {
            return;
        }
        try {
            BaseAgent agent = runner.agent();
            if (agent != null) {
                agent.close().blockingAwait();
            }
        } catch (Throwable error) {
            log.warn("关闭 Runtime Agent 树失败, errorType={}", error.getClass().getSimpleName());
        }
    }
}
