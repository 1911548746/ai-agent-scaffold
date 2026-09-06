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

    @Autowired
    public AgentDefinitionLoader(
            AgentDefinitionLoadPipeline pipeline,
            CatalogResourceBinder resourceBinder) {
        this.pipeline = pipeline;
        this.resourceBinder = resourceBinder;
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
        try {
            return pipeline.load(resourceBinder.bindCatalog(catalogLocation), catalogLocation);
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentConfigurationException("agent definition loading failed");
        }
    }
}
