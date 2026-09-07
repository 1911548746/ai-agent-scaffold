package com.hh.config.agent.bootstrap;

import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimeStatusPort;
import com.hh.config.agent.catalog.loader.AgentCatalogLoadResult;
import com.hh.config.agent.catalog.loader.AgentDefinitionLoader;
import com.hh.config.agent.catalog.properties.AgentCatalogLocationProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 手动重新加载完整 catalog 的应用服务。
 *
 * <p>服务串行执行“读取、编译、发布”流程；只有编译器完成原子发布后才记录 Reload 成功，
 * 任意失败都只更新状态诊断，不触碰已有 Runtime。</p>
 */
@Service
public class AgentRuntimeReloadApplicationService {

    private final AgentCatalogLocationProperties catalogLocationProperties;
    private final AgentDefinitionLoader definitionLoader;
    private final AgentCompiler agentCompiler;
    private final RuntimeStatusPort runtimeStatusPort;

    public AgentRuntimeReloadApplicationService(
            AgentCatalogLocationProperties catalogLocationProperties,
            AgentDefinitionLoader definitionLoader,
            AgentCompiler agentCompiler,
            RuntimeStatusPort runtimeStatusPort) {
        this.catalogLocationProperties = catalogLocationProperties;
        this.definitionLoader = definitionLoader;
        this.agentCompiler = agentCompiler;
        this.runtimeStatusPort = runtimeStatusPort;
    }

    /** 使用部署配置中的唯一 catalog 入口执行一次完整 Reload。 */
    public synchronized ReloadResult reload() {
        long startedAt = System.nanoTime();
        try {
            AgentCatalogLoadResult loadResult = definitionLoader.loadResult(catalogLocationProperties.getPath());
            List<AgentDefinition> enabledDefinitions = loadResult.definitions().stream()
                    .filter(AgentDefinition::isEnabled)
                    .toList();
            agentCompiler.compileAndPublish(enabledDefinitions, loadResult.configDigest());

            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            runtimeStatusPort.recordCompile(duration);
            runtimeStatusPort.recordReloadSuccess();
            return ReloadResult.success(runtimeStatusPort.current(), duration.toMillis());
        } catch (Exception exception) {
            Duration duration = Duration.ofNanos(System.nanoTime() - startedAt);
            runtimeStatusPort.recordReloadFailure(duration, exception);
            return ReloadResult.failure(runtimeStatusPort.current(), duration.toMillis());
        }
    }

    /** Actuator 使用的稳定结果，不携带异常对象或 ADK 实例。 */
    public record ReloadResult(
            String status,
            long generation,
            String configDigest,
            int runtimeCount,
            long durationMs,
            String failureSummary) {

        private static ReloadResult success(RuntimeStatus status, long durationMs) {
            return new ReloadResult("success", status.generation(), status.configDigest(),
                    status.runtimeCount(), durationMs, null);
        }

        private static ReloadResult failure(RuntimeStatus status, long durationMs) {
            return new ReloadResult("failure", status.generation(), status.configDigest(),
                    status.runtimeCount(), durationMs, status.lastFailureSummary());
        }
    }
}
