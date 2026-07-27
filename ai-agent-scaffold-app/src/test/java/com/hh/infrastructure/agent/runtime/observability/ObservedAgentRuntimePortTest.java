package com.hh.infrastructure.agent.runtime.observability;

import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeAdapter;
import com.hh.infrastructure.agent.agent.runtime.observability.AgentRuntimeObservability;
import com.hh.infrastructure.agent.agent.runtime.observability.ObservedAgentRuntimePort;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证显式端口装饰器完整承接调用边界观测，且不改变底层适配器的调用与取消语义。
 */
class ObservedAgentRuntimePortTest {

    private AdkAgentRuntimeAdapter delegate;
    private SimpleMeterRegistry meterRegistry;
    private ObservedAgentRuntimePort observedPort;

    @BeforeEach
    void setUp() {
        delegate = mock(AdkAgentRuntimeAdapter.class);
        meterRegistry = new SimpleMeterRegistry();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meterRegistry));
        observedPort = new ObservedAgentRuntimePort(
                delegate,
                new AgentRuntimeObservability(observationRegistry, meterRegistry));
    }

    @Test
    void synchronousInvocationRecordsDeliveredResponsesAndSessionCreation() {
        when(delegate.createSession("agent-a", "user-a")).thenReturn("session-a");
        when(delegate.run("agent-a", "user-a", "session-a", "hello"))
                .thenReturn(new ChatResult(List.of("first", "second")));

        assertThat(observedPort.createSession("agent-a", "user-a")).isEqualTo("session-a");
        assertThat(observedPort.run("agent-a", "user-a", "session-a", "hello").contents())
                .containsExactly("first", "second");

        assertThat(meterRegistry.find(AgentRuntimeObservability.SESSION_COUNTER_NAME)
                .tag("agent.id", "agent-a").counter().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentRuntimeObservability.RESPONSE_COUNTER_NAME)
                .tags("agent.id", "agent-a", "mode", "sync").counter().count()).isEqualTo(2);
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "sync", "outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void synchronousFailureRecordsErrorWithoutChangingTheException() {
        IllegalStateException failure = new IllegalStateException("provider failure");
        when(delegate.run("agent-a", "user-a", "session-a", "hello")).thenThrow(failure);

        assertThatThrownBy(() -> observedPort.run("agent-a", "user-a", "session-a", "hello"))
                .isSameAs(failure);
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "sync", "outcome", "error")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void streamCancellationCancelsUpstreamAndRecordsOneTerminalOutcome() {
        AtomicInteger upstreamCancellations = new AtomicInteger();
        when(delegate.runStream("agent-a", "user-a", "session-a", "hello"))
                .thenReturn(observer -> upstreamCancellations::incrementAndGet);

        var subscription = observedPort.runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(noOpObserver());
        subscription.cancel();
        subscription.cancel();

        assertThat(upstreamCancellations).hasValue(1);
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "stream", "outcome", "cancelled")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void streamCompletionPreventsLaterCancellationFromOverwritingOutcome() {
        AtomicInteger upstreamCancellations = new AtomicInteger();
        when(delegate.runStream("agent-a", "user-a", "session-a", "hello"))
                .thenReturn(observer -> {
                    observer.onEvent(new ChatEvent("done"));
                    observer.onComplete();
                    return upstreamCancellations::incrementAndGet;
                });

        var subscription = observedPort.runStream("agent-a", "user-a", "session-a", "hello")
                .subscribe(noOpObserver());
        subscription.cancel();

        assertThat(upstreamCancellations).hasValue(0);
        assertThat(meterRegistry.find(AgentRuntimeObservability.INVOCATION_OBSERVATION_NAME)
                .tags("agent.id", "agent-a", "mode", "stream", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(meterRegistry.find(AgentRuntimeObservability.RESPONSE_COUNTER_NAME)
                .tags("agent.id", "agent-a", "mode", "stream").counter().count()).isEqualTo(1);
        verify(delegate).runStream("agent-a", "user-a", "session-a", "hello");
    }

    private ChatStreamObserver noOpObserver() {
        return new ChatStreamObserver() {
            @Override
            public void onEvent(ChatEvent event) {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
