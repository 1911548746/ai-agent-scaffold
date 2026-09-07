package com.hh.infrastructure.agent.agent.runtime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Runtime 发布元数据、Reload 计数和无标签指标的一致性。 */
class AdkAgentRuntimeRegistryStatusTest {

    @Test
    void publishesDigestAndRecordsCompileAndReloadState() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry(meters);

        registry.publish(List.of(), "digest-1");
        registry.recordCompile(Duration.ofMillis(12));
        registry.recordReloadSuccess();
        registry.recordReloadFailure(Duration.ofMillis(7), new IllegalStateException("password=secret"));

        assertThat(registry.current().generation()).isEqualTo(1);
        assertThat(registry.current().configDigest()).isEqualTo("digest-1");
        assertThat(registry.current().runtimeCount()).isZero();
        assertThat(registry.current().reloadSuccessCount()).isEqualTo(1);
        assertThat(registry.current().reloadFailureCount()).isEqualTo(1);
        assertThat(registry.current().lastCompileDurationMs()).isEqualTo(7);
        assertThat(registry.current().lastFailureSummary())
                .isEqualTo("IllegalStateException: password=***");
        assertThat(meters.get("ai.agent.runtime.reload.success").counter().count()).isEqualTo(1);
        assertThat(meters.get("ai.agent.runtime.reload.failure").counter().count()).isEqualTo(1);
        assertThat(meters.get("ai.agent.runtime.compile").timer().count()).isEqualTo(2);
        assertThat(meters.get("ai.agent.runtime.generation").gauge().value()).isEqualTo(1);
    }

    @Test
    void successfulPublishesAdvanceGeneration() {
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry(new SimpleMeterRegistry());
        registry.publish(List.of(), "first");

        assertThat(registry.current().generation()).isEqualTo(1);
        registry.publish(List.of(), "second");
        assertThat(registry.current().generation()).isEqualTo(2);
    }
}
