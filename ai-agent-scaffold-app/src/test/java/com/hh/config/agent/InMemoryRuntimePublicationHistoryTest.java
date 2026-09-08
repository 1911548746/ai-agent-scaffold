package com.hh.config.agent;

import com.hh.application.agent.model.RuntimeReloadTrigger;
import com.hh.application.agent.model.RuntimeStatus;
import com.hh.config.agent.runtime.reload.AgentRuntimeReloadProperties;
import com.hh.config.agent.runtime.reload.InMemoryRuntimePublicationHistory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证发布历史只保留最近配置数量，并以最新记录优先。 */
class InMemoryRuntimePublicationHistoryTest {

    @Test
    void keepsOnlyTheConfiguredNumberOfRecentEntries() {
        AgentRuntimeReloadProperties properties = new AgentRuntimeReloadProperties();
        properties.setHistorySize(2);
        InMemoryRuntimePublicationHistory history = new InMemoryRuntimePublicationHistory(properties);
        RuntimeStatus status = new RuntimeStatus(1, "digest", 1, null, 0, 0, null, null);

        history.record(RuntimeReloadTrigger.STARTUP, "digest-1", status,
                Duration.ofMillis(1), "success", null);
        history.record(RuntimeReloadTrigger.MANUAL, "digest-2", status,
                Duration.ofMillis(2), "success", null);
        history.record(RuntimeReloadTrigger.FILE_WATCH, "digest-3", status,
                Duration.ofMillis(3), "failure", "safe failure");

        assertThat(history.recent()).hasSize(2);
        assertThat(history.recent().get(0).configDigest()).isEqualTo("digest-3");
        assertThat(history.recent().get(1).configDigest()).isEqualTo("digest-2");
    }
}
