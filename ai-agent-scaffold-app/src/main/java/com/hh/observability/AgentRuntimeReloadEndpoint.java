package com.hh.observability;

import com.hh.config.agent.runtime.reload.AgentRuntimeReloadApplicationService;
import com.hh.application.agent.model.RuntimePublication;
import com.hh.application.agent.port.RuntimePublicationHistoryPort;
import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimeStatusPort;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Agent Runtime 的最小手动 Reload 端点。
 *
 * <p>端点不接受 catalog 路径或其他请求参数，避免把任意文件读取能力暴露给管理调用方。</p>
 */
@Component
@Endpoint(id = "agentRuntime")
public class AgentRuntimeReloadEndpoint {

    private final AgentRuntimeReloadApplicationService reloadService;
    private final RuntimeStatusPort runtimeStatusPort;
    private final RuntimePublicationHistoryPort publicationHistory;

    public AgentRuntimeReloadEndpoint(
            AgentRuntimeReloadApplicationService reloadService,
            RuntimeStatusPort runtimeStatusPort,
            RuntimePublicationHistoryPort publicationHistory) {
        this.reloadService = reloadService;
        this.runtimeStatusPort = runtimeStatusPort;
        this.publicationHistory = publicationHistory;
    }

    /** 查询当前运行时和最近发布尝试的脱敏元数据。 */
    @ReadOperation
    public RuntimeState state() {
        return RuntimeState.of(runtimeStatusPort.current(), publicationHistory.recent());
    }

    @WriteOperation
    public AgentRuntimeReloadApplicationService.ReloadResult reload() {
        return reloadService.reload();
    }

    public record RuntimeState(
            long generation,
            String configDigest,
            int runtimeCount,
            java.time.Instant publishedAt,
            long reloadSuccessCount,
            long reloadFailureCount,
            Long lastCompileDurationMs,
            String lastFailureSummary,
            java.util.List<RuntimePublication> history) {

        private static RuntimeState of(RuntimeStatus status, java.util.List<RuntimePublication> history) {
            return new RuntimeState(status.generation(), status.configDigest(), status.runtimeCount(),
                    status.publishedAt(), status.reloadSuccessCount(), status.reloadFailureCount(),
                    status.lastCompileDurationMs(), status.lastFailureSummary(), history);
        }
    }
}
