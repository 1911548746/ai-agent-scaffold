package com.hh.config;

import com.hh.config.agent.AgentDefinitionLoader;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.valobj.AgentInfo;
import com.hh.domain.agent.model.valobj.ChatModel;
import com.hh.domain.agent.model.valobj.Module;
import com.hh.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.hh.domain.agent.service.IArmoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    private IArmoryService armoryService;

    @Test
    void disabledCatalogDoesNotLoadOrAssembleAgents() {
        AiAgentAutoConfigProperties properties = new AiAgentAutoConfigProperties();
        properties.setEnabled(false);

        // enabled=false 必须在读取任意模块资源前返回，避免禁用配置仍创建外部工具。
        new AiAgentAutoConfig(properties, definitionLoader, armoryService)
                .afterSingletonsInstantiated();

        assertThat(properties.getTables()).isEmpty();
        verifyNoInteractions(definitionLoader, armoryService);
    }

    @Test
    void assemblesOnlyEnabledDefinitionsAndPublishesMatchingApiTables() throws Exception {
        AiAgentAutoConfigProperties properties = new AiAgentAutoConfigProperties();
        properties.setEnabled(true);
        AgentDefinition enabled = definition("enabled-agent", true, "100000");
        AgentDefinition disabled = definition("disabled-agent", false, "100002");
        // 编译器可以保留禁用定义供静态校验，但旧 HTTP API 和 Armory 只能看见启用项。
        when(definitionLoader.load(properties)).thenReturn(List.of(enabled, disabled));

        new AiAgentAutoConfig(properties, definitionLoader, armoryService)
                .afterSingletonsInstantiated();

        assertThat(properties.getTables()).containsOnlyKeys("enabled-agent");
        verify(armoryService).acceptArmoryAgents(argThat(tables ->
                tables.size() == 1 && "100000".equals(tables.get(0).getAgentInfo().getAgentId())));
        assertThat(properties.getTables().values())
                .extracting(table -> table.getAgentInfo().getAgentId())
                .containsExactly("100000");
    }

    /**
     * 构造最小定义作为桥接测试夹具，不需要初始化 ADK Runner。
     */
    private AgentDefinition definition(String id, boolean enabled, String agentId) {
        AgentInfo agentInfo = new AgentInfo();
        agentInfo.setAgentId(agentId);
        agentInfo.setAgentName(id);
        agentInfo.setAgentDesc(id);

        ChatModel model = new ChatModel();
        Module.Runner runner = new Module.Runner();
        runner.setAgentName(id);
        runner.setPersistence(false);
        runner.setPluginNameList(List.of());
        runner.setContextCompress(new Module.ContextCompress());

        return AgentDefinition.builder()
                .id(id)
                .enabled(enabled)
                .sourcePath("test:" + id)
                .appName(id)
                .agentInfo(agentInfo)
                .rootAgentName(id)
                .chatModel(model)
                .runner(runner)
                .build();
    }
}
