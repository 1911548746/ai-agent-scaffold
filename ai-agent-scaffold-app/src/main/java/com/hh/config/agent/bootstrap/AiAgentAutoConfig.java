package com.hh.config.agent.bootstrap;

import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.config.agent.catalog.properties.AgentCatalogLocationProperties;
import com.hh.config.agent.catalog.loader.AgentDefinitionLoader;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.application.agent.port.AgentRuntimeCompiler;
import com.hh.application.agent.port.RuntimeStatusPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.time.Duration;

/**
 * 在 Spring 单例初始化完成后，将模块化 catalog 编译并发布为完整的 ADK Runtime 快照。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({AgentCatalogProperties.class, AgentCatalogLocationProperties.class})
public class AiAgentAutoConfig implements SmartInitializingSingleton {

    private final AgentCatalogProperties properties;
    private final AgentCatalogLocationProperties catalogLocationProperties;
    private final AgentDefinitionLoader definitionLoader;
    private final AgentCompiler agentCompiler;
    private final AgentRuntimeCompiler runtimeCompiler;
    private final RuntimeStatusPort runtimeStatusPort;

    public AiAgentAutoConfig(
            AgentCatalogProperties properties,
            AgentCatalogLocationProperties catalogLocationProperties,
            AgentDefinitionLoader definitionLoader,
            AgentCompiler agentCompiler,
            AgentRuntimeCompiler runtimeCompiler,
            RuntimeStatusPort runtimeStatusPort) {
        this.properties = properties;
        this.catalogLocationProperties = catalogLocationProperties;
        this.definitionLoader = definitionLoader;
        this.agentCompiler = agentCompiler;
        this.runtimeCompiler = runtimeCompiler;
        this.runtimeStatusPort = runtimeStatusPort;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            // enabled 关闭时发布空快照，避免同一 Spring Context 中残留旧运行时。
            runtimeCompiler.clear();
            log.info("Ai Agent 自动装配已关闭");
            return;
        }

        long startedAt = System.nanoTime();
        try {
            // 先完成所有资源的静态校验和引用解析；配置不合法时不能进入 ADK 对象创建阶段。
            var loadResult = definitionLoader.loadResult(properties, catalogLocationProperties.getPath());
            List<AgentDefinition> definitions = loadResult.definitions();
            // catalog 可保留禁用示例供校验，但只有启用项才保持旧 API 中“可查询、可运行”的语义。
            List<AgentDefinition> enabledDefinitions = definitions.stream()
                    .filter(AgentDefinition::isEnabled)
                    .toList();
            // 日志只记录可公开的装配摘要，不能序列化包含模型密钥和 MCP header 的完整配置。
            enabledDefinitions.forEach(definition -> log.info(
                    "Ai Agent 配置编译完成 id={}, appName={}, agentId={}, root={}",
                    definition.getId(),
                    definition.getAppName(),
                    definition.getAgentDescriptor().getAgentId(),
                    definition.getRootAgentName()));

            // AgentCompiler 在临时上下文完成全部 ADK 装配，最后单次替换 Registry 快照。
            agentCompiler.compileAndPublish(enabledDefinitions, loadResult.configDigest());
        } finally {
            runtimeStatusPort.recordCompile(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

}
