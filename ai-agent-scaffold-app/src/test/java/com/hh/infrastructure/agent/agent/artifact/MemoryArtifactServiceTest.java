package com.hh.infrastructure.agent.agent.artifact;

import com.google.adk.agents.RunConfig;
import com.google.adk.summarizer.EventCompactor;
import com.google.adk.runner.Runner;
import com.hh.application.agent.model.AttachmentUpload;
import com.hh.infrastructure.agent.agent.artifact.routing.RuntimeAttachmentStoragePort;
import com.hh.infrastructure.agent.agent.artifact.store.memory.MemoryArtifactService;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntimeRegistry;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.types.enums.ContextCompressTypeEnum;
import com.hh.types.exception.AppException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证上传路由与 ADK Runner 对 memory 后端使用同一实例。 */
class MemoryArtifactServiceTest {

    @Test
    void runtimeRouterStoresLoadsAndDeletesThroughRunnerInstance() {
        MemoryArtifactService service = new MemoryArtifactService("app-a");
        Runner runner = mock(Runner.class);
        when(runner.artifactService()).thenReturn(service);
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry(new SimpleMeterRegistry());
        registry.publish(List.of(new AdkAgentRuntime(
                "app-a", "agent-a", "Agent A", "test", runner, RunConfig.builder().build(), plan())));
        RuntimeAttachmentStoragePort router = new RuntimeAttachmentStoragePort(registry);

        var reference = router.store("app-a", "user-a", "session-a", List.of(
                new AttachmentUpload("note.txt", "text/plain", new byte[]{1, 2, 3}))).get(0);

        assertThat(runner.artifactService()).isSameAs(service);
        assertThat(service.listArtifactKeys("app-a", "user-a", "session-a").blockingGet().filenames())
                .containsExactly(reference.artifactName());
        assertThat(service.listVersions(
                "app-a", "user-a", "session-a", reference.artifactName()).blockingGet())
                .containsExactly(0);
        assertThat(service.loadArtifact(
                "app-a", "user-a", "session-a", reference.artifactName(), null).blockingGet())
                .isNotNull();
        router.deleteSession("app-a", "user-a", "session-a");
        service.loadArtifact("app-a", "user-a", "session-a", reference.artifactName(), null)
                .test().assertNoValues().assertComplete();
    }

    @Test
    void rejectsUnknownAndDuplicateAppNames() {
        AdkAgentRuntimeRegistry registry = new AdkAgentRuntimeRegistry(new SimpleMeterRegistry());
        Runner first = mock(Runner.class);
        Runner second = mock(Runner.class);

        assertThatThrownBy(() -> registry.publish(List.of(
                new AdkAgentRuntime("same-app", "agent-a", "A", "test", first, RunConfig.builder().build(), plan()),
                new AdkAgentRuntime("same-app", "agent-b", "B", "test", second, RunConfig.builder().build(), plan()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate appName");
        assertThatThrownBy(() -> new RuntimeAttachmentStoragePort(registry).store(
                "missing-app", "user", "session", List.of()))
                .isInstanceOf(AppException.class);
    }

    private RuntimeCompactionPlan plan() {
        return new RuntimeCompactionPlan(ContextCompressTypeEnum.FIXED_SIZE, mock(EventCompactor.class), Integer.MAX_VALUE);
    }
}
