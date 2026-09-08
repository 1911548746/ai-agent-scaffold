package com.hh.config.agent;

import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimeStatusPort;
import com.hh.application.agent.port.RuntimePublicationHistoryPort;
import com.hh.config.agent.runtime.complier.AgentCompiler;
import com.hh.config.agent.runtime.reload.AgentRuntimeReloadApplicationService;
import com.hh.config.agent.catalog.loader.AgentCatalogLoadResult;
import com.hh.config.agent.catalog.loader.AgentDefinitionLoader;
import com.hh.config.agent.catalog.properties.AgentCatalogLocationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证手动 Reload 只在完整编译成功后计数，并且失败时保留旧状态。 */
@ExtendWith(MockitoExtension.class)
class AgentRuntimeReloadApplicationServiceTest {

    @Mock
    private AgentDefinitionLoader definitionLoader;
    @Mock
    private AgentCompiler agentCompiler;
    @Mock
    private RuntimeStatusPort runtimeStatusPort;
    @Mock
    private RuntimePublicationHistoryPort publicationHistory;

    @Test
    void successfulReloadPublishesDigestAndRecordsSuccess() {
        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        AgentCatalogLoadResult loadResult = new AgentCatalogLoadResult(List.of(), "digest-1");
        RuntimeStatus status = new RuntimeStatus(2, "digest-1", 1, null, 1, 0, 12L, null);
        when(definitionLoader.loadResult(location.getPath())).thenReturn(loadResult);
        when(runtimeStatusPort.current()).thenReturn(status);

        AgentRuntimeReloadApplicationService service = new AgentRuntimeReloadApplicationService(
                location, definitionLoader, agentCompiler, runtimeStatusPort, publicationHistory);

        AgentRuntimeReloadApplicationService.ReloadResult result = service.reload();

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.generation()).isEqualTo(2);
        assertThat(result.configDigest()).isEqualTo("digest-1");
        verify(agentCompiler).compileAndPublish(List.of(), "digest-1");
        verify(runtimeStatusPort).recordReloadSuccess();
        verify(runtimeStatusPort).recordCompile(any());
    }

    @Test
    void failedReloadRecordsFailureWithoutCallingSuccess() {
        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        RuntimeStatus oldStatus = new RuntimeStatus(3, "old-digest", 1, null, 1, 0, 9L, null);
        IllegalStateException failure = new IllegalStateException("apiKey=secret");
        when(definitionLoader.loadResult(location.getPath())).thenReturn(
                new AgentCatalogLoadResult(List.of(), "new-digest"));
        doThrow(failure).when(agentCompiler).compileAndPublish(any(), eq("new-digest"));
        when(runtimeStatusPort.current()).thenReturn(oldStatus);

        AgentRuntimeReloadApplicationService service = new AgentRuntimeReloadApplicationService(
                location, definitionLoader, agentCompiler, runtimeStatusPort, publicationHistory);

        AgentRuntimeReloadApplicationService.ReloadResult result = service.reload();

        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.generation()).isEqualTo(3);
        assertThat(result.configDigest()).isEqualTo("old-digest");
        verify(runtimeStatusPort).recordReloadFailure(any(), eq(failure));
    }

    @Test
    void automaticReloadSkipsCompilationWhenDigestIsUnchanged() {
        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        RuntimeStatus current = new RuntimeStatus(4, "same-digest", 2, null, 3, 1, 18L, null);
        when(definitionLoader.loadResult(location.getPath()))
                .thenReturn(new AgentCatalogLoadResult(List.of(), "same-digest"));
        when(runtimeStatusPort.current()).thenReturn(current);

        AgentRuntimeReloadApplicationService service = new AgentRuntimeReloadApplicationService(
                location, definitionLoader, agentCompiler, runtimeStatusPort, publicationHistory);

        AgentRuntimeReloadApplicationService.ReloadResult result = service.reloadAutomatically();

        assertThat(result.status()).isEqualTo("unchanged");
        assertThat(result.generation()).isEqualTo(4);
        verifyNoInteractions(agentCompiler, publicationHistory);
    }

    @Test
    void manualReloadCompilesEvenWhenDigestIsUnchanged() {
        AgentCatalogLocationProperties location = new AgentCatalogLocationProperties();
        RuntimeStatus current = new RuntimeStatus(4, "same-digest", 2, null, 3, 1, 18L, null);
        when(definitionLoader.loadResult(location.getPath()))
                .thenReturn(new AgentCatalogLoadResult(List.of(), "same-digest"));
        when(runtimeStatusPort.current()).thenReturn(current);

        AgentRuntimeReloadApplicationService service = new AgentRuntimeReloadApplicationService(
                location, definitionLoader, agentCompiler, runtimeStatusPort, publicationHistory);

        AgentRuntimeReloadApplicationService.ReloadResult result = service.reload();

        assertThat(result.status()).isEqualTo("success");
        verify(agentCompiler).compileAndPublish(List.of(), "same-digest");
        verify(runtimeStatusPort).recordReloadSuccess();
    }
}
