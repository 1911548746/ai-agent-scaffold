package com.hh.config;

import com.hh.config.agent.AgentConfigurationException;
import com.hh.config.agent.AgentDefinitionLoader;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.hh.domain.agent.service.IArmoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 Spring 单例初始化完成后，将模块化 catalog 编译为当前 ADK 装配链仍可识别的配置对象。
 *
 * <p>这里保留旧的 {@link IArmoryService} 入口，避免 YAML-MOD-01 同时触及 HTTP 和 Runner 调用方。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AiAgentAutoConfigProperties.class)
public class AiAgentAutoConfig implements SmartInitializingSingleton {

    private final AiAgentAutoConfigProperties properties;
    private final AgentDefinitionLoader definitionLoader;
    private final IArmoryService armoryService;

    public AiAgentAutoConfig(
            AiAgentAutoConfigProperties properties,
            AgentDefinitionLoader definitionLoader,
            IArmoryService armoryService) {
        this.properties = properties;
        this.definitionLoader = definitionLoader;
        this.armoryService = armoryService;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            // enabled 关闭时主动清空运行时表，避免同一 Spring Context 中残留旧的 Agent 元数据。
            properties.setTables(new LinkedHashMap<>());
            log.info("Ai Agent 自动装配已关闭");
            return;
        }

        // 先完成所有资源的静态校验和引用解析；配置不合法时不能进入 ADK 对象创建阶段。
        List<AgentDefinition> definitions = definitionLoader.load(properties);
        // catalog 可保留禁用示例供校验，但只有启用项才保持旧 API 中“可查询、可运行”的语义。
        List<AgentDefinition> enabledDefinitions = definitions.stream()
                .filter(AgentDefinition::isEnabled)
                .toList();
        Map<String, AiAgentConfigTableVO> tables = new LinkedHashMap<>();
        enabledDefinitions.forEach(definition -> tables.put(definition.getId(), definition.toLegacyConfig()));
        // ChatService 仍通过该表查询 Agent 列表，因此在调用 Armory 前同步发布编译结果。
        properties.setTables(tables);

        // 日志只记录可公开的装配摘要，不能序列化包含模型密钥和 MCP header 的完整配置。
        enabledDefinitions.forEach(definition -> log.info(
                "Ai Agent 配置编译完成 id={}, appName={}, agentId={}, root={}",
                definition.getId(),
                definition.getAppName(),
                definition.getAgentInfo().getAgentId(),
                definition.getRootAgentName()));

        try {
            // 保留原有策略树装配流程，YAML-MOD-01 仅替换其输入配置的来源和校验方式。
            armoryService.acceptArmoryAgents(enabledDefinitions.stream()
                    .map(AgentDefinition::toLegacyConfig)
                    .toList());
        } catch (Exception e) {
            // ADK 或工具初始化异常可能携带供应商细节，启动日志只保留异常类型。
            log.error("Agent catalog ADK assembly failed, errorType={}", e.getClass().getSimpleName());
            throw new AgentConfigurationException("Agent catalog ADK assembly failed");
        }
    }

}
