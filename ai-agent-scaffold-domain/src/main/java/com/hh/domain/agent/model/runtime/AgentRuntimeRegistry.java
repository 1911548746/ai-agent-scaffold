package com.hh.domain.agent.model.runtime;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 按 agentId 发布 Agent 运行时的不可变快照。
 *
 * <p>编译阶段先在 Registry 外完成全部 ADK 资源创建，最后通过一次 {@link #publish(Collection)}
 * 替换整个 Map。读取方始终只会看到旧快照或完整新快照，不会观察到半装配状态。</p>
 */
@Component
public class AgentRuntimeRegistry {

    private final AtomicReference<Map<String, AgentRuntime>> snapshot = new AtomicReference<>(Map.of());

    /**
     * 发布一批完整运行时。重复 ID 在替换前失败，因此不会破坏当前已发布快照。
     */
    public void publish(Collection<AgentRuntime> runtimes) {
        Map<String, AgentRuntime> candidate = new LinkedHashMap<>();
        for (AgentRuntime runtime : runtimes) {
            AgentRuntime nonNullRuntime = Objects.requireNonNull(runtime, "runtime must not be null");
            String agentId = nonNullRuntime.agentId();
            if (agentId.isBlank()) {
                throw new IllegalArgumentException("agentId must not be blank");
            }
            if (candidate.putIfAbsent(agentId, nonNullRuntime) != null) {
                throw new IllegalArgumentException("duplicate agentId: " + agentId);
            }
        }

        // 复制后再包装为只读 LinkedHashMap，既阻断外部修改，又保持 catalog 的展示顺序。
        snapshot.set(Collections.unmodifiableMap(new LinkedHashMap<>(candidate)));
    }

    /**
     * 请求处理只通过该方法查找运行时，禁止退回 ApplicationContext 的动态 Bean 查询。
     */
    public Optional<AgentRuntime> find(String agentId) {
        return Optional.ofNullable(snapshot.get().get(agentId));
    }

    /**
     * 返回当前不可变快照，用于健康检查和只读 Agent 列表查询。
     */
    public Map<String, AgentRuntime> snapshot() {
        return snapshot.get();
    }
}
