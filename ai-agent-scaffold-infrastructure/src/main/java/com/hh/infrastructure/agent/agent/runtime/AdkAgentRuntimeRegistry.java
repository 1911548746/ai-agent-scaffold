package com.hh.infrastructure.agent.agent.runtime;

import com.hh.domain.agent.model.runtime.AgentDescriptor;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存 ADK 运行时的不可变发布快照。
 *
 * <p>候选 Runtime 在 Registry 外完成全部装配；发布时只替换一个不可变 generation。旧 generation
 * 立即停止接受新租约，待既有租约归零后异步关闭，保证切换期间不会访问已关闭的 Runner。</p>
 */
@Component
public class AdkAgentRuntimeRegistry {

    private final AtomicReference<RuntimeGeneration> current =
            new AtomicReference<>(RuntimeGeneration.empty(0));
    private final AtomicLong nextGeneration = new AtomicLong(1);

    /**
     * 发布一批完整运行时。重复 ID 在替换前失败，因此不会破坏当前已发布快照。
     */
    public synchronized void publish(Collection<AdkAgentRuntime> runtimes) {
        RuntimeGeneration candidate = new RuntimeGeneration(
                nextGeneration.getAndIncrement(), runtimes);
        RuntimeGeneration previous = current.getAndSet(candidate);
        // 原子切换已经完成；旧 generation 只允许现有租约继续使用。
        previous.beginDraining();
    }

    /** 切换为空快照；当前调用继续排空，新调用将得到 Agent 不存在错误。 */
    public synchronized void clear() {
        RuntimeGeneration empty = RuntimeGeneration.empty(nextGeneration.getAndIncrement());
        RuntimeGeneration previous = current.getAndSet(empty);
        previous.beginDraining();
    }

    public Optional<RuntimeLease> acquire(String agentId) {
        return current.get().acquireByAgentId(agentId);
    }

    public Optional<RuntimeLease> acquireByAppName(String appName) {
        return current.get().acquireByAppName(appName);
    }

    /** 为需要长时间持有根 Agent 的 ADK Web 开发 Loader 获取整代租约。 */
    public List<RuntimeLease> acquireAll() {
        return current.get().acquireAll();
    }

    /** 只读展示使用；资源调用不得从此 Map 中直接取得 Runner。 */
    public Map<String, AdkAgentRuntime> snapshot() {
        return current.get().snapshot();
    }

    public int size() {
        return current.get().size();
    }

    public List<AgentDescriptor> descriptors() {
        return current.get().snapshot().values().stream()
                .map(AdkAgentRuntime::toAgentDescriptor)
                .toList();
    }

    /**
     * Spring Context 关闭时先切空快照，再按正常 generation 规则等待请求排空。
     *
     * <p>这里不直接逐个关闭 Runtime，避免绕过在途调用的 lease；关闭线程是否能在进程退出前
     * 完成由宿主的优雅停机窗口决定。</p>
     */
    @PreDestroy
    void shutdown() {
        clear();
    }
}
