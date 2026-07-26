package com.hh.test.ai;

import com.hh.application.agent.port.AgentRuntimePort;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受保护的真实模型冒烟测试。
 *
 * <p>仅由 {@code -Plive-agent-it verify} 发现；即使是 live 测试也必须走 application runtime
 * port，避免测试重新把 ADK Runner 泄漏到 app 层。它不能作为离线测试或 CI 结论。</p>
 */
@SpringBootTest
@Tag("live-agent-it")
@EnabledIfEnvironmentVariable(named = "MODEL_API_KEY", matches = ".+")
class AiAgentAutoConfigIT {

    @Resource
    private AgentRuntimePort agentRuntimePort;

    @Test
    void runsFirstAgentThroughRuntimePort() {
        String sessionId = agentRuntimePort.createSession("100000", "user001");
        assertThat(agentRuntimePort.run("100000", "user001", sessionId, "北京今天的天气怎么样？"))
                .isNotNull();
    }

    @Test
    void runsSecondPromptThroughRuntimePort() {
        String sessionId = agentRuntimePort.createSession("100000", "user001");
        assertThat(agentRuntimePort.run("100000", "user001", sessionId, "你好"))
                .isNotNull();
    }
}
