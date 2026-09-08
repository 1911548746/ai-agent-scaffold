package com.hh.observability;

import com.hh.config.agent.runtime.reload.AgentRuntimeReloadApplicationService;
import com.hh.application.agent.port.RuntimePublicationHistoryPort;
import com.hh.application.agent.port.RuntimeStatusPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证手动 Reload 使用 Spring Boot Actuator 可接受的端点标识。 */
class AgentRuntimeReloadEndpointTest {

    @Test
    void usesLowercaseActuatorEndpointId() {
        Endpoint annotation = AgentRuntimeReloadEndpoint.class.getAnnotation(Endpoint.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.id()).isEqualTo("agentRuntime");
        assertThatCode(() -> EndpointId.of(annotation.id())).doesNotThrowAnyException();
    }

    @Test
    void delegatesWriteOperationWithoutRequestPayload() {
        AgentRuntimeReloadApplicationService service =
                mock(AgentRuntimeReloadApplicationService.class);
        RuntimeStatusPort runtimeStatusPort = mock(RuntimeStatusPort.class);
        RuntimePublicationHistoryPort publicationHistory = mock(RuntimePublicationHistoryPort.class);
        when(service.reload()).thenReturn(new AgentRuntimeReloadApplicationService.ReloadResult(
                "success", 2, "digest", 1, 20, null));
        AgentRuntimeReloadEndpoint endpoint = new AgentRuntimeReloadEndpoint(
                service, runtimeStatusPort, publicationHistory);

        assertThat(endpoint.reload().status()).isEqualTo("success");
        verify(service).reload();
    }
}
