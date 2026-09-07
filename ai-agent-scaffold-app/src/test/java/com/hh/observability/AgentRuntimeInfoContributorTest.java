package com.hh.observability;

import com.hh.application.agent.model.RuntimeStatus;
import com.hh.application.agent.port.RuntimeStatusPort;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Runtime info 只暴露约定的脱敏运维字段。 */
class AgentRuntimeInfoContributorTest {

    @Test
    void contributesRuntimeMetadataWithoutRuntimeObjects() {
        RuntimeStatusPort port = mock(RuntimeStatusPort.class);
        when(port.current()).thenReturn(new RuntimeStatus(
                4, "digest-4", 2, null, 3, 1, 18L, "IllegalStateException: configuration failed"));

        org.springframework.boot.actuate.info.Info.Builder builder =
                new org.springframework.boot.actuate.info.Info.Builder();
        new AgentRuntimeInfoContributor(port).contribute(builder);

        Map<String, Object> details = builder.build().getDetails();
        assertThat(details).containsKey("aiAgentRuntime");
        assertThat(details.get("aiAgentRuntime").toString())
                .contains("generation", "configDigest", "reloadFailureCount", "lastFailure")
                .doesNotContain("Runner", "apiKey", "password");
    }
}
