package com.hh.test.ai;

import com.hh.application.agent.port.AgentRuntimePort;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 受保护的真实模型冒烟测试。
 *
 * <p>即使是 live 测试也必须走 application runtime port，避免测试重新把 ADK Runner 泄漏到
 * app 层。默认禁用，不能作为离线测试或 CI 结论。</p>
 */
@SpringBootTest
@Disabled("需要真实模型供应商，使用受保护的 live-agent-it 环境手动执行")
class AiAgentAutoConfigTest {

    @Resource
    private AgentRuntimePort agentRuntimePort;

    @Test
    void runsFirstAgentThroughRuntimePort() {
        String sessionId = agentRuntimePort.createSession("100000", "user001");
        agentRuntimePort.run("100000", "user001", sessionId, "北京今天的天气怎么样？");
    }

    @Test
    void runsSecondPromptThroughRuntimePort() {
        String sessionId = agentRuntimePort.createSession("100000", "user001");
        agentRuntimePort.run("100000", "user001", sessionId, "你好");
    }
}
