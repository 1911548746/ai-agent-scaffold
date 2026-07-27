package com.hh.infrastructure.agent.runtime.observability;

import com.hh.infrastructure.agent.agent.runtime.observability.AgentRuntimeObservability;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runtime 指标的离线测试；SimpleMeterRegistry 只保存在内存中，不向任何采集端发送数据。
 */
class AgentRuntimeObservabilityTest {

    private SimpleMeterRegistry meterRegistry;
    private AgentRuntimeObservability observability;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        // 生产环境由 Actuator 自动注册 handler；测试显式安装以验证 observation 会生成 Timer。
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        observability = new AgentRuntimeObservability(observationRegistry, meterRegistry);
    }

    @Test
    void successfulInvocationRecordsOutcomeAndResponseMetrics() {
        AgentRuntimeObservability.Invocation invocation = observability.startInvocation("agent-a", "stream");

        String result = invocation.observe(() -> {
            invocation.recordResponse();
            invocation.recordResponse();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "stream", "outcome", "success")
                .timer()).isNotNull();
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "stream", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentRuntimeObservability.FIRST_RESPONSE_TIMER_NAME)
                .tags("agent.id", "agent-a", "mode", "stream")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentRuntimeObservability.RESPONSE_COUNTER_NAME)
                .tags("agent.id", "agent-a", "mode", "stream")
                .counter().count()).isEqualTo(2);
    }

    @Test
    void errorAndCancellationProduceStableIdempotentOutcomes() {
        AgentRuntimeObservability.Invocation failed = observability.startInvocation("agent-a", "sync");
        assertThatThrownBy(() -> failed.observe(() -> {
            throw new IllegalStateException("provider failure");
        })).isInstanceOf(IllegalStateException.class);

        AgentRuntimeObservability.Invocation cancelled = observability.startInvocation("agent-a", "stream");
        assertThat(cancelled.cancel()).isTrue();
        assertThat(cancelled.cancel()).isFalse();
        assertThat(cancelled.success()).isFalse();

        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags(
                        "agent.id", "agent-a",
                        "mode", "sync",
                        "outcome", "error",
                        "error.type", "IllegalStateException")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "stream", "outcome", "cancelled")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.getMeters())
                .flatExtracting(meter -> meter.getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain("provider failure");
    }

    @Test
    void sessionCounterAndMetricTagsNeverContainRequestIdentity() {
        observability.recordSessionCreated("agent-a");

        assertThat(meterRegistry.find(AgentRuntimeObservability.SESSION_COUNTER_NAME)
                .tag("agent.id", "agent-a")
                .counter().count()).isEqualTo(1);
        assertThat(meterRegistry.getMeters())
                .extracting(Meter::getId)
                .flatExtracting(Meter.Id::getTags)
                .extracting(tag -> tag.getKey())
                .doesNotContain("user.id", "session.id", "message", "prompt", "response");
    }
}
