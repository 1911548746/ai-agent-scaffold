package com.hh.config.agent.catalog.loader.context;

import com.hh.config.agent.catalog.binding.AgentModuleConfig;
import com.hh.config.agent.catalog.binding.ModelModuleConfig;
import com.hh.config.agent.catalog.binding.SkillModuleConfig;
import com.hh.config.agent.catalog.binding.ToolModuleConfig;
import com.hh.config.agent.catalog.binding.WorkflowModuleConfig;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单次 catalog 编译的临时上下文。 */
@Data
public class AgentDefinitionLoadContext {

    private Map<String, ModelModuleConfig> models = new LinkedHashMap<>();
    private Map<String, ToolModuleConfig> tools = new LinkedHashMap<>();
    private Map<String, SkillModuleConfig> skills = new LinkedHashMap<>();
    private Map<String, AgentModuleConfig> agents = new LinkedHashMap<>();
    private Map<String, WorkflowModuleConfig> workflows = new LinkedHashMap<>();
    private List<String> workflowOrder = new ArrayList<>();
    private List<LocatedApplication> applications = new ArrayList<>();
    private List<AgentDefinition> definitions = List.of();

    public record LocatedApplication(
            AgentCatalogProperties.ApplicationDefinition definition,
            String sourcePath) {
    }
}
