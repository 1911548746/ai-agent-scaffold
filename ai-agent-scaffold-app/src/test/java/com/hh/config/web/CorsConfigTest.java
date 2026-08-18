package com.hh.config.web;

import com.hh.config.application.ApplicationModeProperties;
import com.hh.config.application.CorsProperties;
import com.hh.types.enums.ApplicationMode;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证本地回环来源和线上环境 allowlist 的 CORS 边界。 */
class CorsConfigTest {

    @Test
    void localModeOnlyAllowsLoopbackOrigins() {
        ApplicationModeProperties mode = new ApplicationModeProperties();
        mode.setMode(ApplicationMode.LOCAL);

        CorsConfiguration configuration = register(mode, new CorsProperties());

        assertThat(configuration.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(configuration.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    @Test
    void onlineModeUsesConfiguredOrigins() {
        ApplicationModeProperties mode = new ApplicationModeProperties();
        CorsProperties cors = new CorsProperties();
        mode.setMode(ApplicationMode.ONLINE);
        cors.setAllowedOrigins(List.of("https://console.example"));

        CorsConfiguration configuration = register(mode, cors);

        assertThat(configuration.checkOrigin("https://console.example")).isEqualTo("https://console.example");
        assertThat(configuration.checkOrigin("https://evil.example")).isNull();
    }

    private CorsConfiguration register(ApplicationModeProperties mode, CorsProperties cors) {
        ExposedCorsRegistry registry = new ExposedCorsRegistry();
        new CorsConfig(mode, cors).addCorsMappings(registry);
        return registry.configurations().get("/**");
    }

    private static final class ExposedCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
