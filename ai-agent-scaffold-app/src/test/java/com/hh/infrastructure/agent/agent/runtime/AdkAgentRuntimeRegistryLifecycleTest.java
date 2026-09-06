package com.hh.infrastructure.agent.agent.runtime;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.Runner;
import com.google.adk.summarizer.EventCompactor;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.types.enums.ContextCompressTypeEnum;
import io.reactivex.rxjava3.core.Completable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 Runtime generation 切换、租约排空和候选索引校验。 */
class AdkAgentRuntimeRegistryLifecycleTest {

    @Test
    void oldGenerationClosesOnlyAfterLastLeaseIsReleased() {
        BaseAgent oldAgent = mock(BaseAgent.class);
        when(oldAgent.close()).thenReturn(Completable.complete());
        Runner oldRunner = mock(Runner.class);
        when(oldRunner.agent()).thenReturn(oldAgent);

        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(runtime("old-app", "old-agent", oldRunner)));
        RuntimeLease oldLease = registry.acquire("old-agent").orElseThrow();

        Runner newRunner = mock(Runner.class);
        registry.publish(List.of(runtime("new-app", "new-agent", newRunner)));

        assertThat(registry.acquire("old-agent")).isEmpty();
        verify(oldAgent, org.mockito.Mockito.never()).close();

        oldLease.close();
        verify(oldAgent, org.mockito.Mockito.timeout(1000)).close();
        oldLease.close();
        verify(oldAgent).close();
    }

    @Test
    void clearRejectsNewLeasesButAllowsExistingLeaseToFinish() {
        BaseAgent oldAgent = mock(BaseAgent.class);
        when(oldAgent.close()).thenReturn(Completable.complete());
        Runner runner = mock(Runner.class);
        when(runner.agent()).thenReturn(oldAgent);

        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        registry.publish(List.of(runtime("app", "agent", runner)));
        RuntimeLease lease = registry.acquireByAppName("app").orElseThrow();

        registry.clear();

        assertThat(registry.acquire("agent")).isEmpty();
        verify(oldAgent, org.mockito.Mockito.never()).close();
        lease.close();
        verify(oldAgent, org.mockito.Mockito.timeout(1000)).close();
    }

    @Test
    void rejectsDuplicateAgentAndAppBeforeReplacingCurrentGeneration() {
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry();
        Runner currentRunner = mock(Runner.class);
        registry.publish(List.of(runtime("current-app", "current-agent", currentRunner)));

        assertThatThrownBy(() -> registry.publish(List.of(
                runtime("candidate-app", "same-agent", mock(Runner.class)),
                runtime("other-app", "same-agent", mock(Runner.class)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate agentId");
        try (RuntimeLease lease = registry.acquire("current-agent").orElseThrow()) {
            assertThat(lease.runtime().appName()).isEqualTo("current-app");
        }

        assertThatThrownBy(() -> registry.publish(List.of(
                runtime("same-app", "agent-a", mock(Runner.class)),
                runtime("same-app", "agent-b", mock(Runner.class)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate appName");
        try (RuntimeLease lease = registry.acquireByAppName("current-app").orElseThrow()) {
            assertThat(lease.runtime().agentId()).isEqualTo("current-agent");
        }
    }

    private AdkAgentRuntime runtime(String appName, String agentId, Runner runner) {
        return new AdkAgentRuntime(
                appName,
                agentId,
                agentId,
                "test",
                runner,
                RunConfig.builder().build(),
                new RuntimeCompactionPlan(
                        ContextCompressTypeEnum.FIXED_SIZE,
                        mock(EventCompactor.class),
                        Integer.MAX_VALUE),
                RuntimeResourceScope.of(runner, List.of()));
    }
}
