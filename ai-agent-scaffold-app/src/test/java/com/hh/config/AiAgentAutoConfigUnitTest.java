package com.hh.config;

import com.hh.config.agent.AgentDefinitionLoader;
import com.hh.config.agent.properties.AgentCatalogProperties;
import com.hh.application.agent.port.AgentRuntimeCompiler;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.config.agent.AgentCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证启动桥接层的发布边界，不创建真实 Runner 或模型客户端。
 */
@ExtendWith(MockitoExtension.class)
class AiAgentAutoConfigUnitTest {

    @Mock
    private AgentDefinitionLoader definitionLoader;
    @Mock
    private AgentCompiler agentCompiler;
    @Mock
    private AgentRuntimeCompiler runtimeCompiler;

    @Test
    void disabledCatalogDoesNotLoadOrAssembleAgents() {
        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setEnabled(false);

        // enabled=false 必须在读取任意模块资源前返回，避免禁用配置仍创建外部工具。
        new AiAgentAutoConfig(properties, definitionLoader, agentCompiler, runtimeCompiler)
                .afterSingletonsInstantiated();

        verify(runtimeCompiler).clear();
        verifyNoInteractions(definitionLoader, agentCompiler);
    }

    @Test
    void compilesOnlyEnabledDefinitions() {
        AgentCatalogProperties properties = new AgentCatalogProperties();
        properties.setEnabled(true);
        AgentDefinition enabled = definition("enabled-agent", true, "100000");
        AgentDefinition disabled = definition("disabled-agent", false, "100002");
        // 编译器可以保留禁用定义供静态校验，但旧 HTTP API 和 Armory 只能看见启用项。
        when(definitionLoader.load(properties)).thenReturn(List.of(enabled, disabled));

        new AiAgentAutoConfig(properties, definitionLoader, agentCompiler, runtimeCompiler)
                .afterSingletonsInstantiated();

        verify(agentCompiler).compileAndPublish(argThat(definitions ->
                definitions.size() == 1 && "100000".equals(definitions.get(0).getAgentDescriptor().getAgentId())));
    }

    /**
     * 构造最小定义作为桥接测试夹具，不需要初始化 ADK Runner。
     */
    private AgentDefinition definition(String id, boolean enabled, String agentId) {
        com.hh.domain.agent.model.runtime.AgentDescriptor agentDescriptor = new com.hh.domain.agent.model.runtime.AgentDescriptor();
        agentDescriptor.setAgentId(agentId);
        agentDescriptor.setAgentName(id);
        agentDescriptor.setAgentDesc(id);

        com.hh.domain.agent.model.definition.ChatModelDefinition model = new com.hh.domain.agent.model.definition.ChatModelDefinition();
        com.hh.domain.agent.model.definition.RunnerDefinition runner = new com.hh.domain.agent.model.definition.RunnerDefinition();
        runner.setAgentName(id);
        runner.setPersistence(false);
        runner.setPluginNameList(List.of());
        runner.setContextCompress(new com.hh.domain.agent.model.definition.ContextCompressionDefinition());

        return AgentDefinition.builder()
                .id(id)
                .enabled(enabled)
                .sourcePath("test:" + id)
                .appName(id)
                .agentDescriptor(agentDescriptor)
                .rootAgentName(id)
                .chatModel(model)
                .runner(runner)
                .build();
    }
}
