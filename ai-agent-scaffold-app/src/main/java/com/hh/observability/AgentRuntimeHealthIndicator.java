package com.hh.observability;

import com.hh.config.agent.properties.AgentCatalogProperties;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntimeRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 判断 Agent Runtime Registry 是否具备接收请求的完整快照。
 *
 * <p>该检查只报告启用状态和运行时数量，不输出 Agent ID、模型配置或外部工具信息。
 * 数据库健康由 Actuator 自带的 {@code db} 指标独立负责，两者共同组成 readiness。</p>
 */
@Component
public class AgentRuntimeHealthIndicator implements HealthIndicator {

    private final AgentCatalogProperties catalogProperties;
    private final AdkAgentRuntimeRegistry runtimeRegistry;

    public AgentRuntimeHealthIndicator(
            AgentCatalogProperties catalogProperties,
            AdkAgentRuntimeRegistry runtimeRegistry) {
        this.catalogProperties = catalogProperties;
        this.runtimeRegistry = runtimeRegistry;
    }

    @Override
    public Health health() {
        int runtimeCount = runtimeRegistry.snapshot().size();
        if (!catalogProperties.isEnabled()) {
            // 显式关闭 Agent 能力属于有效部署状态，不能因为空 Registry 阻断整个应用 readiness。
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("runtimeCount", runtimeCount)
                    .build();
        }
        if (runtimeCount == 0) {
            // 配置已启用却没有任何已发布 Runtime，说明服务无法处理 Agent 请求。
            return Health.down()
                    .withDetail("enabled", true)
                    .withDetail("runtimeCount", 0)
                    .build();
        }
        return Health.up()
                .withDetail("enabled", true)
                .withDetail("runtimeCount", runtimeCount)
                .build();
    }
}
