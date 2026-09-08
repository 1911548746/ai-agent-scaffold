package com.hh.config.agent.runtime.reload;

import com.hh.application.agent.model.RuntimePublication;
import com.hh.application.agent.model.RuntimeReloadTrigger;
import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimePublicationHistoryPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单 JVM 有界发布历史。
 *
 * <p>历史用于排查配置发布过程，不作为回滚数据源；应用重启后自然清空。</p>
 */
@Component
public class InMemoryRuntimePublicationHistory implements RuntimePublicationHistoryPort {

    private final int capacity;
    private final Deque<RuntimePublication> entries = new ArrayDeque<>();

    public InMemoryRuntimePublicationHistory(AgentRuntimeReloadProperties properties) {
        if (properties.getHistorySize() < 1) {
            throw new IllegalArgumentException("history size must be positive");
        }
        this.capacity = properties.getHistorySize();
    }

    @Override
    public synchronized void record(
            RuntimeReloadTrigger trigger,
            String configDigest,
            RuntimeStatus status,
            Duration duration,
            String outcome,
            String failureSummary) {
        Duration safeDuration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
        RuntimePublication publication = new RuntimePublication(
                status.generation(),
                configDigest,
                trigger,
                Instant.now(),
                safeDuration.toMillis(),
                outcome,
                failureSummary);
        entries.addFirst(publication);
        while (entries.size() > capacity) {
            entries.removeLast();
        }
    }

    @Override
    public synchronized List<RuntimePublication> recent() {
        return List.copyOf(new ArrayList<>(entries));
    }
}
