package com.hh.config.agent.catalog.loader;

import com.hh.config.agent.catalog.AgentConfigurationException;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadPipeline;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
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

    @Autowired
    public AgentDefinitionLoader(AgentDefinitionLoadPipeline pipeline) {
        this.pipeline = pipeline;
    }

    public List<AgentDefinition> load(AgentCatalogProperties catalog) {
        try {
            return pipeline.load(catalog);
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgentConfigurationException("agent definition loading failed");
        }
    }
}
