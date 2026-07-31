package com.hh.dev.adkweb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdkWebDevelopmentLauncherTest {

    @Test
    void shouldUseDefaultOrConfiguredPort() {
        assertThat(AdkWebDevelopmentLauncher.resolvePort(null)).isEqualTo(8000);
        assertThat(AdkWebDevelopmentLauncher.resolvePort(" ")).isEqualTo(8000);
        assertThat(AdkWebDevelopmentLauncher.resolvePort("9000")).isEqualTo(9000);
    }

    @Test
    void shouldRejectInvalidPort() {
        assertThatThrownBy(() -> AdkWebDevelopmentLauncher.resolvePort("0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ADK_WEB_PORT must be between 1 and 65535");
        assertThatThrownBy(() -> AdkWebDevelopmentLauncher.resolvePort("not-a-port"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ADK_WEB_PORT must be a valid integer");
    }

    @Test
    void shouldKeepAdkWebOnLoopbackWithoutDatabaseOrManagementServer() {
        assertThat(AdkWebDevelopmentLauncher.adkWebProperties(8123))
                .containsEntry("server.address", "127.0.0.1")
                .containsEntry("server.port", 8123)
                .containsEntry("management.server.port", -1)
                .containsEntry(
                        "spring.autoconfigure.exclude",
                        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration");
    }
}
