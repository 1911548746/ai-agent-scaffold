package com.hh.config.agent;

import com.google.adk.runner.Runner;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.runtime.AgentRuntimeRegistry;
import com.hh.domain.agent.service.IArmoryService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
                .thenReturn(List.of(runtime("agent-a"), runtime("agent-b")));

        List<AgentDefinition> definitions = List.of(
                definition("agent-a", "agent-a.yml"),
                definition("agent-b", "agent-b.yml"));

        compiler.compileAndPublish(definitions);

        assertThat(registry.snapshot()).containsOnlyKeys("agent-a", "agent-b");
        assertThat(registry.find("agent-a")).isPresent();
        assertThat(registry.find("agent-b")).isPresent();

        // DefinitionLoader 已完成配置编译，Armory 必须直接消费该结果，避免重新构造旧 YAML 表对象。
        ArgumentCaptor<List<AgentDefinition>> captor = ArgumentCaptor.forClass(List.class);
        verify(armoryService).acceptArmoryAgents(captor.capture());
        assertThat(captor.getValue()).isSameAs(definitions);
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
        AgentDescriptor agentDescriptor = new AgentDescriptor();
        agentDescriptor.setAgentId(agentId);
        agentDescriptor.setAgentName(agentId);
        agentDescriptor.setAgentDesc(agentId);

        RunnerDefinition runner = new RunnerDefinition();
        runner.setAgentName(agentId);
        runner.setContextCompress(new ContextCompressionDefinition());

        return AgentDefinition.builder()
                .id(sourcePath)
                .enabled(true)
                .sourcePath(sourcePath)
                .appName(agentId)
                .agentDescriptor(agentDescriptor)
                .rootAgentName(agentId)
                .chatModel(new ChatModelDefinition())
                .runner(runner)
                .build();
    }

    private AgentRuntime runtime(String agentId) {
        return new AgentRuntime(agentId, agentId, agentId, agentId, mock(Runner.class));
    }
}
