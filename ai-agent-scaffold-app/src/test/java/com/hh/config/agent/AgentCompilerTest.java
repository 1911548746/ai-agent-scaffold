package com.hh.config.agent;

import com.google.adk.runner.Runner;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.legacyconfig.AgentInfo;
import com.hh.domain.agent.model.legacyconfig.ChatModel;
import com.hh.domain.agent.model.legacyconfig.Module;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.runtime.AgentRuntimeRegistry;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.service.IArmoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证 AgentCompiler 的临时构建和原子发布边界；不启动 Spring Context、模型、数据库或 MCP。
 */
@ExtendWith(MockitoExtension.class)
class AgentCompilerTest {

    @Mock
    private IArmoryService armoryService;

    @Test
    void assemblyFailureKeepsPreviouslyPublishedSnapshot() throws Exception {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
        registry.publish(List.of(runtime("old-agent")));
        AgentCompiler compiler = new AgentCompiler(armoryService, registry);
        when(armoryService.acceptArmoryAgents(anyList()))
                .thenThrow(new IllegalStateException("simulated adk failure"));

        assertThatThrownBy(() -> compiler.compileAndPublish(List.of(definition("new-agent", "new-agent.yml"))))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessage("Agent catalog ADK assembly failed");

        // 编译失败时 publish 未发生，请求仍只能看到上一份完整快照。
        assertThat(registry.snapshot()).containsOnlyKeys("old-agent");
    }

    @Test
    void allRuntimesArePublishedTogetherAfterAssemblySucceeds() throws Exception {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
        AgentCompiler compiler = new AgentCompiler(armoryService, registry);
        when(armoryService.acceptArmoryAgents(anyList()))
                .thenReturn(List.of(register("agent-a"), register("agent-b")));

        compiler.compileAndPublish(List.of(
                definition("agent-a", "agent-a.yml"),
                definition("agent-b", "agent-b.yml")));

        assertThat(registry.snapshot()).containsOnlyKeys("agent-a", "agent-b");
        assertThat(registry.find("agent-a")).isPresent();
        assertThat(registry.find("agent-b")).isPresent();
    }

    @Test
    void duplicateRuntimeAgentIdFailsBeforeAnyAdkResourceIsBuilt() {
        AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
        registry.publish(List.of(runtime("old-agent")));
        AgentCompiler compiler = new AgentCompiler(armoryService, registry);

        assertThatThrownBy(() -> compiler.compileAndPublish(List.of(
                definition("same-agent", "first-agent.yml"),
                definition("same-agent", "second-agent.yml"))))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("second-agent.yml")
                .hasMessageContaining("first-agent.yml");

        verifyNoInteractions(armoryService);
        assertThat(registry.snapshot()).containsOnlyKeys("old-agent");
    }

    private AgentDefinition definition(String agentId, String sourcePath) {
        AgentInfo agentInfo = new AgentInfo();
        agentInfo.setAgentId(agentId);
        agentInfo.setAgentName(agentId);
        agentInfo.setAgentDesc(agentId);

        Module.Runner runner = new Module.Runner();
        runner.setAgentName(agentId);
        runner.setContextCompress(new Module.ContextCompress());

        return AgentDefinition.builder()
                .id(sourcePath)
                .enabled(true)
                .sourcePath(sourcePath)
                .appName(agentId)
                .agentInfo(agentInfo)
                .rootAgentName(agentId)
                .chatModel(new ChatModel())
                .runner(runner)
                .build();
    }

    private AiAgentRegisterVO register(String agentId) {
        return AiAgentRegisterVO.builder()
                .appName(agentId)
                .agentId(agentId)
                .agentName(agentId)
                .agentDesc(agentId)
                .runner(mock(Runner.class))
                .build();
    }

    private AgentRuntime runtime(String agentId) {
        return new AgentRuntime(agentId, agentId, agentId, agentId, mock(Runner.class));
    }
}
