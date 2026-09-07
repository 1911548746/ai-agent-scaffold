package com.hh.config.agent.catalog.loader;

import com.hh.config.agent.catalog.AgentConfigurationException;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadPipeline;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.config.agent.catalog.loader.resource.CatalogResourceBinder;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent Definition 加载兼容门面。
 *
 * <p>具体的资源发现、校验、图处理和 Definition 编译由 app 层策略树完成；保留本类作为
 * 启动配置和现有测试的稳定入口。</p>
 */
@Component
public class AgentDefinitionLoader {

    private final AgentDefinitionLoadPipeline pipeline;
    private final CatalogResourceBinder resourceBinder;
    private final ConfigSnapshotDigest snapshotDigest;

    @Autowired
    public AgentDefinitionLoader(
            AgentDefinitionLoadPipeline pipeline,
            CatalogResourceBinder resourceBinder,
            ConfigSnapshotDigest snapshotDigest) {
        this.pipeline = pipeline;
        this.resourceBinder = resourceBinder;
        this.snapshotDigest = snapshotDigest;
    }

    /** 使用已绑定的 catalog 执行一次独立编译。 */
    public List<AgentDefinition> load(AgentCatalogProperties catalog) {
        return load(catalog, null);
    }

    /**
     * 使用已绑定的 catalog 编译，并使用实际入口作为相对资源的解析基准。
     *
     * <p>启动阶段由 Spring import 绑定 catalog 内容；实际入口来自独立部署配置，避免外部快照中
     * 遗留的诊断字段影响资源定位。</p>
     */
    public List<AgentDefinition> load(AgentCatalogProperties catalog, String catalogLocation) {
        try {
            return pipeline.load(catalog, catalogLocation);
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentConfigurationException("agent definition loading failed");
        }
    }

    /**
     * 从指定入口重新读取完整 catalog，并执行一次独立编译。
     *
     * <p>该入口供后续热更新触发器复用；本批不负责触发调用方，也不发布 Runtime。</p>
     */
    public List<AgentDefinition> load(String catalogLocation) {
        return loadResult(catalogLocation).definitions();
    }

    /**
     * 加载已绑定 catalog，并返回本次资源快照的稳定摘要。
     *
     * <p>摘要计算只读取本次 catalog 声明并实际解析的 YAML 资源；资源路径和内容均使用明确
     * 的长度边界写入哈希，避免不同路径或内容组合产生歧义。</p>
     */
    public AgentCatalogLoadResult loadResult(AgentCatalogProperties catalog, String catalogLocation) {
        try {
            List<AgentDefinition> definitions = pipeline.load(catalog, catalogLocation);
            return new AgentCatalogLoadResult(
                    definitions,
                    snapshotDigest.calculate(catalog, catalogLocation, null));
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentConfigurationException("agent definition loading failed");
        }
    }

    /** 从指定入口加载完整 catalog，并同时计算实际资源快照摘要。 */
    public AgentCatalogLoadResult loadResult(String catalogLocation) {
        try {
            CatalogResourceBinder.LocatedCatalog locatedCatalog = resourceBinder.bindCatalogResource(catalogLocation);
            AgentCatalogProperties catalog = locatedCatalog.catalog();
            List<AgentDefinition> definitions = pipeline.load(catalog, catalogLocation);
            return new AgentCatalogLoadResult(
                    definitions,
                    snapshotDigest.calculate(catalog, catalogLocation, locatedCatalog.resource()));
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentConfigurationException("agent definition loading failed");
        }
    }
}
