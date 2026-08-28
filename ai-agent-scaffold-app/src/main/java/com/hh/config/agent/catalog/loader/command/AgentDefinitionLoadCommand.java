package com.hh.config.agent.catalog.loader.command;

import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Loader Pipeline 的输入，只携带 Spring 已绑定的 catalog。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentDefinitionLoadCommand {

    private AgentCatalogProperties catalog;

}
