package com.hh.dev.adkweb;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.adk.agents.BaseAgent;
import com.google.adk.web.AdkWebServer;
import com.google.adk.web.AgentLoader;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdkWebBootCompatibilityTest {

    private ConfigurableApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void shouldCreateTokenBufferParserWithBootManagedJacksonCore() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TokenBuffer tokenBuffer = new TokenBuffer(objectMapper, false);
        try (JsonParser parser = tokenBuffer.asParser()) {
            // AgentRunRequest 使用 ObjectMapper.convertValue 时会走到同一构造器；该断言可防止
            // Wrench 内嵌的旧 jackson-core 再次抢先加载而导致 NoSuchMethodError。
            assertThat(parser).isNotNull();
        } finally {
            tokenBuffer.close();
        }
    }

    @Test
    void shouldServeAgentListUiAndInMemorySessionApiOnSpringBoot3() throws Exception {
        AgentLoader loader = new SingleAgentLoader("test-app", mock(BaseAgent.class));
        SpringApplication application = new SpringApplication(AdkWebServer.class);
        application.setRegisterShutdownHook(false);

        Map<String, Object> properties = new LinkedHashMap<>(
                AdkWebDevelopmentLauncher.adkWebProperties(0));
        properties.put("server.port", 0);
        application.setDefaultProperties(properties);
        application.addInitializers(configurableContext ->
                configurableContext.getBeanFactory().registerSingleton("agentLoader", loader));

        context = application.run(
                "--spring.config.name=adk-web-test",
                "--adk.agents.loader=custom");
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpResponse<String> apps = get(client, port, "/list-apps");
        assertThat(apps.statusCode()).isEqualTo(200);
        assertThat(apps.body()).contains("test-app");

        HttpResponse<String> ui = get(client, port, "/dev-ui/");
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).containsIgnoringCase("<html");

        HttpRequest createSession = HttpRequest.newBuilder()
                .uri(uri(port, "/apps/test-app/users/test-user/sessions/test-session"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> created = client.send(
                createSession, HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("test-session");

        HttpResponse<String> sessions = get(
                client, port, "/apps/test-app/users/test-user/sessions");
        assertThat(sessions.statusCode()).isEqualTo(200);
        assertThat(sessions.body()).contains("test-session");
    }

    private HttpResponse<String> get(HttpClient client, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri(port, path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private static final class SingleAgentLoader implements AgentLoader {

        private final String appName;
        private final BaseAgent agent;

        private SingleAgentLoader(String appName, BaseAgent agent) {
            this.appName = appName;
            this.agent = agent;
        }

        @Override
        @Nonnull
        public ImmutableList<String> listAgents() {
            return ImmutableList.of(appName);
        }

        @Override
        public BaseAgent loadAgent(String name) {
            if (!appName.equals(name)) {
                throw new NoSuchElementException("Agent not found: " + name);
            }
            return agent;
        }
    }
}
