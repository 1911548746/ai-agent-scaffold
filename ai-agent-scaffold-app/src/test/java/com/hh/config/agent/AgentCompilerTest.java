package com.hh.config.agent;

import com.hh.application.agent.port.AgentRuntimeCompiler;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

/**
 * 验证 app 配置层只通过 application 端口触发编译，不接触 ADK Runtime 或 Registry。
 */
@ExtendWith(MockitoExtension.class)
class AgentCompilerTest {

    @Mock
    private AgentRuntimeCompiler runtimeCompiler;

    @Test
    void assemblyFailureIsConvertedToSafeConfigurationException() throws Exception {
        AgentCompiler compiler = new AgentCompiler(runtimeCompiler);
        doThrow(new IllegalStateException("simulated adk failure"))
                .when(runtimeCompiler).compileAndPublish(anyList());

        assertThatThrownBy(() -> compiler.compileAndPublish(List.of(definition("new-agent", "new-agent.yml"))))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessage("Agent catalog ADK assembly failed");
    }

    @Test
    void passesValidatedDefinitionsDirectlyToRuntimeCompiler() throws Exception {
        AgentCompiler compiler = new AgentCompiler(runtimeCompiler);
        List<AgentDefinition> definitions = List.of(
                definition("agent-a", "agent-a.yml"),
                definition("agent-b", "agent-b.yml"));

        compiler.compileAndPublish(definitions);

        // DefinitionLoader 已完成配置编译，不能再组装旧 YAML 表对象。
        ArgumentCaptor<List<AgentDefinition>> captor = ArgumentCaptor.forClass(List.class);
        verify(runtimeCompiler).compileAndPublish(captor.capture());
        assertThat(captor.getValue()).isSameAs(definitions);
    }

    @Test
    void duplicateRuntimeAgentIdFailsBeforeInfrastructureAssembly() {
        AgentCompiler compiler = new AgentCompiler(runtimeCompiler);

        assertThatThrownBy(() -> compiler.compileAndPublish(List.of(
                definition("same-agent", "first-agent.yml"),
                definition("same-agent", "second-agent.yml"))))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("second-agent.yml")
                .hasMessageContaining("first-agent.yml");

        verifyNoInteractions(runtimeCompiler);
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
}
