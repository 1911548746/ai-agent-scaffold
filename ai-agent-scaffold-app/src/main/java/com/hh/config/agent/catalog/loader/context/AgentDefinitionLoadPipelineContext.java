package com.hh.config.agent.catalog.loader.context;

import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import lombok.Data;

/** Loader Pipeline 的运行上下文。 */
@Data
public class AgentDefinitionLoadPipelineContext {

    private AgentCatalogProperties catalog;
    private String catalogPath;
    private AgentDefinitionLoadContext loadContext = new AgentDefinitionLoadContext();
}
