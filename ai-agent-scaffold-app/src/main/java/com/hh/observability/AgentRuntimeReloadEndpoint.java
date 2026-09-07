package com.hh.observability;

import com.hh.config.agent.bootstrap.AgentRuntimeReloadApplicationService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
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

    public AgentRuntimeReloadEndpoint(AgentRuntimeReloadApplicationService reloadService) {
        this.reloadService = reloadService;
    }

    @WriteOperation
    public AgentRuntimeReloadApplicationService.ReloadResult reload() {
        return reloadService.reload();
    }
}
