package com.hh.observability;

import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimeStatusPort;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 向 Actuator info 提供脱敏的当前 Runtime 发布状态。 */
@Component
public class AgentRuntimeInfoContributor implements InfoContributor {

    private final RuntimeStatusPort runtimeStatusPort;

    public AgentRuntimeInfoContributor(RuntimeStatusPort runtimeStatusPort) {
        this.runtimeStatusPort = runtimeStatusPort;
    }

    @Override
    public void contribute(Info.Builder builder) {
        RuntimeStatus status = runtimeStatusPort.current();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("generation", status.generation());
        details.put("configDigest", status.configDigest());
        details.put("runtimeCount", status.runtimeCount());
        details.put("publishedAt", status.publishedAt());
        details.put("reloadSuccessCount", status.reloadSuccessCount());
        details.put("reloadFailureCount", status.reloadFailureCount());
        details.put("compileDurationMs", status.lastCompileDurationMs());
        details.put("lastFailure", status.lastFailureSummary());
        builder.withDetail("aiAgentRuntime", details);
    }
}
