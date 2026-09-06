package com.hh.infrastructure.agent.agent.runtime;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 一组同时发布的 Runtime 及其排空状态。
 *
 * <p>generation 被 Registry 替换后不再接受新租约，但已取得的租约仍可继续使用；引用数归零
 * 后才关闭其中的 Runtime 资源。状态方法使用同一把锁，避免“最后一次获取”和“开始排空”竞态。</p>
 */
@Slf4j
public final class RuntimeGeneration {

    private final long number;  // 版本号
    private final Map<String, AdkAgentRuntime> byAgentId;
    private final Map<String, AdkAgentRuntime> byAppName;

    private int activeLeases;   // 引用计数
    private boolean draining;   // 是否开始排空
    private boolean closeStarted;   // 是否开始关闭

    RuntimeGeneration(long number, Collection<AdkAgentRuntime> runtimes) {
        this.number = number;
        Map<String, AdkAgentRuntime> agentIndex = new LinkedHashMap<>();
        Map<String, AdkAgentRuntime> appIndex = new LinkedHashMap<>();
        for (AdkAgentRuntime runtime : Objects.requireNonNull(runtimes, "runtimes must not be null")) {
            AdkAgentRuntime value = Objects.requireNonNull(runtime, "runtime must not be null");
            if (value.agentId().isBlank()) {
                throw new IllegalArgumentException("agentId must not be blank");
            }
            if (agentIndex.putIfAbsent(value.agentId(), value) != null) {
                throw new IllegalArgumentException("duplicate agentId: " + value.agentId());
            }
            if (value.appName().isBlank()) {
                throw new IllegalArgumentException("appName must not be blank");
            }
            if (appIndex.putIfAbsent(value.appName(), value) != null) {
                throw new IllegalArgumentException("duplicate appName: " + value.appName());
            }
        }
        this.byAgentId = immutable(agentIndex);
        this.byAppName = immutable(appIndex);
    }

    static RuntimeGeneration empty(long number) {
        return new RuntimeGeneration(number, java.util.List.of());
    }

    public long number() {
        return number;
    }

    public int size() {
        return byAgentId.size();
    }

    /** 仅供健康检查、展示和测试读取不可变索引；资源操作必须使用 acquire。 */
    public Map<String, AdkAgentRuntime> snapshot() {
        return byAgentId;
    }

    Optional<RuntimeLease> acquireByAgentId(String agentId) {
        return acquire(byAgentId.get(agentId));
    }

    Optional<RuntimeLease> acquireByAppName(String appName) {
        return acquire(byAppName.get(appName));
    }

    java.util.List<RuntimeLease> acquireAll() {
        synchronized (this) {
            if (draining || closeStarted) {
                return java.util.List.of();
            }
            activeLeases += byAgentId.size();
            return byAgentId.values().stream()
                    .map(runtime -> new RuntimeLease(this, runtime))
                    .toList();
        }
    }

    /** 标记旧 generation，不阻塞发布线程；最后一个租约释放时完成关闭。 */
    void beginDraining() {
        boolean closeNow;
        synchronized (this) {
            if (draining || closeStarted) {
                return;
            }
            draining = true;
            closeNow = activeLeases == 0;
            if (closeNow) {
                closeStarted = true;
            }
        }
        if (closeNow) {
            // 即使当前没有请求，也不能让发布线程承担 ADK/MCP 关闭耗时。
            scheduleClose();
        }
    }

    /** 候选 Runtime 从未发布时直接关闭，不进入异步排空。 */
    void closeCandidate() {
        synchronized (this) {
            if (closeStarted) {
                return;
            }
            draining = true;
            closeStarted = true;
        }
        closeResources();
    }

    void release() {
        boolean closeNow = false;
        synchronized (this) {
            if (activeLeases <= 0) {
                throw new IllegalStateException("runtime lease count underflow");
            }
            activeLeases--;
            if (activeLeases == 0) {
                notifyAll();
                if (draining && !closeStarted) {
                    closeStarted = true;
                    closeNow = true;
                }
            }
        }
        if (closeNow) {
            // 最后一个请求只负责触发后台关闭，不把资源回收耗时叠加到请求尾延迟。
            scheduleClose();
        }
    }

    private Optional<RuntimeLease> acquire(AdkAgentRuntime runtime) {
        if (runtime == null) {
            return Optional.empty();
        }
        synchronized (this) {
            if (draining || closeStarted) {
                return Optional.empty();
            }
            activeLeases++;
            return Optional.of(new RuntimeLease(this, runtime));
        }
    }

    /** 将 Runtime 资源关闭放到后台线程，保证切换和请求释放路径快速返回。 */
    private void scheduleClose() {
        CompletableFuture.runAsync(this::closeResources);
    }

    private void closeResources() {
        byAgentId.values().forEach(runtime -> {
            try {
                runtime.close();
            } catch (Throwable error) {
                log.warn("关闭 Runtime 资源失败, generation={}, agentId={}, errorType={}",
                        number, runtime.agentId(), error.getClass().getSimpleName());
            }
        });
    }

    private Map<String, AdkAgentRuntime> immutable(Map<String, AdkAgentRuntime> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
