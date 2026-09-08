package com.hh.config.agent.runtime.reload;

import com.hh.application.agent.model.RuntimeReloadTrigger;
import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimePublicationHistoryPort;
import com.hh.application.agent.port.RuntimeStatusPort;
import com.hh.config.agent.runtime.complier.AgentCompiler;
import com.hh.config.agent.catalog.loader.AgentCatalogLoadResult;
import com.hh.config.agent.catalog.loader.AgentDefinitionLoader;
import com.hh.config.agent.catalog.properties.AgentCatalogLocationProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 编排完整 catalog 的读取、digest 判断、候选编译和 Runtime 发布。
 *
 * <p>手动 Reload 强制编译，即使 digest 未变化；文件监听 Reload 则先比较 digest，避免文件系统
 * 产生重复事件时反复重建模型客户端、MCP 和其他 Runtime 资源。</p>
 */
@Service
public class AgentRuntimeReloadApplicationService {

    private final AgentCatalogLocationProperties catalogLocationProperties;
    private final AgentDefinitionLoader definitionLoader;
    private final AgentCompiler agentCompiler;
    private final RuntimeStatusPort runtimeStatusPort;
    private final RuntimePublicationHistoryPort publicationHistory;

    public AgentRuntimeReloadApplicationService(
            AgentCatalogLocationProperties catalogLocationProperties,
            AgentDefinitionLoader definitionLoader,
            AgentCompiler agentCompiler,
            RuntimeStatusPort runtimeStatusPort,
            RuntimePublicationHistoryPort publicationHistory) {
        this.catalogLocationProperties = catalogLocationProperties;
        this.definitionLoader = definitionLoader;
        this.agentCompiler = agentCompiler;
        this.runtimeStatusPort = runtimeStatusPort;
        this.publicationHistory = publicationHistory;
    }

    /** 管理员主动触发的强制 Reload；即使 digest 未变化也重新创建 Runtime。 */
    public synchronized ReloadResult reload() {
        return reloadOnce(RuntimeReloadTrigger.MANUAL, true);
    }

    /** 文件监听触发的一次 Reload 尝试；退避和重试由监听器负责调度。 */
    public synchronized ReloadResult reloadAutomatically() {
        return reloadOnce(RuntimeReloadTrigger.FILE_WATCH, false);
    }

    /** 文件监听的后续重试，仍然使用非强制 digest 去重。 */
    public synchronized ReloadResult retryAutomatically() {
        return reloadOnce(RuntimeReloadTrigger.FILE_WATCH_RETRY, false);
    }

    private ReloadResult reloadOnce(RuntimeReloadTrigger trigger, boolean force) {
        long startedAt = System.nanoTime();
        String candidateDigest = null;
        try {
            AgentCatalogLoadResult loadResult = definitionLoader.loadResult(catalogLocationProperties.getPath());
            candidateDigest = loadResult.configDigest();

            // 只有自动监听路径使用 digest 去重；手动路径必须保留强制恢复 Runtime 资源的语义。
            RuntimeStatus currentStatus = runtimeStatusPort.current();
            if (!force && Objects.equals(candidateDigest,
                    currentStatus == null ? null : currentStatus.configDigest())) {
                return ReloadResult.unchanged(currentStatus == null ? RuntimeStatus.initial() : currentStatus);
            }

            List<AgentDefinition> enabledDefinitions = loadResult.definitions().stream()
                    .filter(AgentDefinition::isEnabled)
                    .toList();
            agentCompiler.compileAndPublish(enabledDefinitions, candidateDigest);

            Duration duration = elapsed(startedAt);
            runtimeStatusPort.recordCompile(duration);
            runtimeStatusPort.recordReloadSuccess();
            RuntimeStatus status = runtimeStatusPort.current();
            publicationHistory.record(trigger, candidateDigest, status, duration, "success", null);
            return ReloadResult.success(status, duration.toMillis());
        } catch (Exception exception) {
            Duration duration = elapsed(startedAt);
            runtimeStatusPort.recordReloadFailure(duration, exception);
            RuntimeStatus status = runtimeStatusPort.current();
            publicationHistory.record(
                    trigger,
                    candidateDigest,
                    status,
                    duration,
                    "failure",
                    status.lastFailureSummary());
            return ReloadResult.failure(status, duration.toMillis());
        }
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
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

        private static ReloadResult unchanged(RuntimeStatus status) {
            return new ReloadResult("unchanged", status.generation(), status.configDigest(),
                    status.runtimeCount(), 0, null);
        }

        private static ReloadResult failure(RuntimeStatus status, long durationMs) {
            return new ReloadResult("failure", status.generation(), status.configDigest(),
                    status.runtimeCount(), durationMs, status.lastFailureSummary());
        }
    }
}
