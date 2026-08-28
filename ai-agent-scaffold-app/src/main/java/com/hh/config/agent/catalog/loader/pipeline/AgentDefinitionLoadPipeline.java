package com.hh.config.agent.catalog.loader.pipeline;

import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.config.agent.catalog.loader.node.RootNode;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Service;

import java.util.List;

/** Agent Definition 加载策略树入口。 */
@Service
public class AgentDefinitionLoadPipeline {

    private final RootNode rootNode;

    public AgentDefinitionLoadPipeline(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public List<AgentDefinition> load(AgentCatalogProperties catalog) throws Exception {
        return rootNode.apply(
                new AgentDefinitionLoadCommand(catalog),
                new AgentDefinitionLoadPipelineContext());
    }

}
