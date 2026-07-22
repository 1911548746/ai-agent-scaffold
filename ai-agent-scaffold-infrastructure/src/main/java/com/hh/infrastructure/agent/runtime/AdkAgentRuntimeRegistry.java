package com.hh.infrastructure.agent.runtime;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 保存 ADK 运行时的不可变发布快照。
 *
 * <p>候选对象必须在 Registry 外完成全部装配；本类仅负责重复 ID 校验和一次性替换，
 * 因而读取方永远只会看到旧快照或完整新快照。</p>
 */
@Component
public class AdkAgentRuntimeRegistry {

    private final AtomicReference<Map<String, AdkAgentRuntime>> snapshot = new AtomicReference<>(Map.of());

    /**
     * 发布一批完整运行时。重复 ID 在替换前失败，因此不会破坏当前已发布快照。
     */
    public void publish(Collection<AdkAgentRuntime> runtimes) {
        Map<String, AdkAgentRuntime> candidate = new LinkedHashMap<>();
        for (AdkAgentRuntime runtime : runtimes) {
            AdkAgentRuntime nonNullRuntime = Objects.requireNonNull(runtime, "runtime must not be null");
            if (nonNullRuntime.agentId().isBlank()) {
                throw new IllegalArgumentException("agentId must not be blank");
            }
            if (candidate.putIfAbsent(nonNullRuntime.agentId(), nonNullRuntime) != null) {
                throw new IllegalArgumentException("duplicate agentId: " + nonNullRuntime.agentId());
            }
        }
        // 复制后再包装为只读 LinkedHashMap，既阻断外部修改，又保持 catalog 的展示顺序。
        snapshot.set(Collections.unmodifiableMap(new LinkedHashMap<>(candidate)));
    }

    /**
     * 请求处理只通过该方法查找运行时，禁止退回 ApplicationContext 的动态 Bean 查询。
     */
    public Optional<AdkAgentRuntime> find(String agentId) {
        return Optional.ofNullable(snapshot.get().get(agentId));
    }

    /**
     * 返回当前不可变快照，用于健康检查和只读 Agent 列表查询。
     */
    public Map<String, AdkAgentRuntime> snapshot() {
        return snapshot.get();
    }
}
