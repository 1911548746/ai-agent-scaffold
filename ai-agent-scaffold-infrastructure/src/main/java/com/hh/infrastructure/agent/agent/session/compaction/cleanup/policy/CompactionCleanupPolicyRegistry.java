package com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy;

import com.hh.types.enums.ContextCompressTypeEnum;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CompactionCleanupPolicy 的策略注册表。
 *
 * <p>使用 Spring 收集全部策略实现；同一 type 重复注册会在启动期失败，防止不同删除语义被静默覆盖。</p>
 */
@Component
public class CompactionCleanupPolicyRegistry {

    private final Map<String, CompactionCleanupPolicy> policies;
    private final CompactionCleanupPolicy defaultPolicy;

    public CompactionCleanupPolicyRegistry(List<CompactionCleanupPolicy> policies) {
        Map<String, CompactionCleanupPolicy> resolved = new LinkedHashMap<>();
        for (CompactionCleanupPolicy policy : policies) {
            CompactionCleanupPolicy previous = resolved.putIfAbsent(policy.type(), policy);
            if (previous != null) {
                throw new IllegalStateException("duplicate compaction cleanup policy: " + policy.type());
            }
        }
        this.policies = Map.copyOf(resolved);
        // RunnerNode 将除 slidingWindow 外的所有 type 都按 fixedSize 装配；注册表的默认策略必须同步。
        this.defaultPolicy = resolved.get(ContextCompressTypeEnum.FIXED_SIZE.getType());
        if (defaultPolicy == null) {
            throw new IllegalStateException("missing fixed-size compaction cleanup policy");
        }
    }

    /**
     * 未注册策略时回退 fixedSize，与 RunnerNode 的 ADK 压缩配置保持一致。
     */
    public CompactionCleanupPolicy resolve(String type) {
        return policies.getOrDefault(type, defaultPolicy);
    }
}
