package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.binding.AgentModuleConfig;
import com.hh.config.agent.catalog.binding.ModelModuleConfig;
import com.hh.config.agent.catalog.binding.SkillModuleConfig;
import com.hh.config.agent.catalog.binding.ToolModuleConfig;
import com.hh.config.agent.catalog.binding.WorkflowModuleConfig;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.resource.CatalogResourceBinder;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadContext;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 模块资源绑定节点。
 *
 * <p>前置条件是 catalog 已通过自身校验；本节点负责发现五类模块 YAML、独立绑定、回填来源路径，
 * 并按稳定 ID 建立索引。它不校验跨模块引用，也不创建领域 Definition。</p>
 *
 * <p>任何资源缺失、绑定失败或同类 ID 重复都会在本节点终止，避免后续节点看到半成品索引。</p>
 */
@Component
public class ModuleBindingNode extends AgentDefinitionLoadNode {

    private final CatalogResourceBinder resourceBinder;
    private final AgentDefinitionConfigSupport support;
    private final ModuleValidationNode moduleValidationNode;

    public ModuleBindingNode(
            CatalogResourceBinder resourceBinder,
            AgentDefinitionConfigSupport support,
            ModuleValidationNode moduleValidationNode) {
        this.resourceBinder = resourceBinder;
        this.support = support;
        this.moduleValidationNode = moduleValidationNode;
    }

    @Override
    protected List<AgentDefinition> doApply(
            AgentDefinitionLoadCommand command,
            AgentDefinitionLoadPipelineContext pipelineContext) throws Exception {
        // 使用同一个加载上下文承接五类模块的中间结果，后续校验节点可以看到完整 ID 索引。
        AgentDefinitionLoadContext context = pipelineContext.getLoadContext();

        // catalog 本身已经在前置节点校验过，此处只读取其资源位置配置。
        var catalog = command.getCatalog();

        // 按固定顺序绑定资源，顺序同时决定可预测的错误顺序和后续 Definition 的稳定顺序。
        context.setModels(load(catalog.getModelResources(), "ai.agent.model", ModelModuleConfig.class,
                ModelModuleConfig::getId, ModelModuleConfig::getSourcePath, "model"));
        context.setTools(load(catalog.getToolResources(), "ai.agent.tool", ToolModuleConfig.class,
                ToolModuleConfig::getId, ToolModuleConfig::getSourcePath, "tool"));
        context.setSkills(load(catalog.getSkillResources(), "ai.agent.skill", SkillModuleConfig.class,
                SkillModuleConfig::getId, SkillModuleConfig::getSourcePath, "skill"));
        context.setAgents(load(catalog.getAgentResources(), "ai.agent.agent", AgentModuleConfig.class,
                AgentModuleConfig::getId, AgentModuleConfig::getSourcePath, "agent"));
        context.setWorkflows(load(catalog.getWorkflowResources(), "ai.agent.workflow", WorkflowModuleConfig.class,
                WorkflowModuleConfig::getId, WorkflowModuleConfig::getSourcePath, "workflow"));

        // 五类资源全部成功绑定后，才把控制权交给引用和语义校验阶段。
        return router(command, pipelineContext);
    }

    private <T> Map<String, T> load(
            List<String> locations,
            String prefix,
            Class<T> type,
            Function<T, String> idExtractor,
            Function<T, String> pathExtractor,
            String kind) {
        // LinkedHashMap 保留资源扫描顺序，同时提供按稳定 ID 查找模块的能力。
        Map<String, T> indexed = new LinkedHashMap<>();

        for (CatalogResourceBinder.LocatedResource resource : resourceBinder.resolve(locations)) {
            // 每个资源使用独立绑定过程，避免不同 YAML 文件的同名字段互相污染。
            T value = resourceBinder.bind(resource, prefix, type);

            // 绑定 DTO 不携带文件来源；回填后所有校验异常都可以定位到具体资源。
            setSourcePath(value, resource.sourcePath());

            // 读取模块 ID，后续跨模块引用都通过这个索引解析。
            String id = idExtractor.apply(value);

            // putIfAbsent 保留第一个定义，以便错误信息同时报告首次和重复出现的位置。
            T previous = indexed.putIfAbsent(id, value);
            if (previous != null) {
                throw support.error(pathExtractor.apply(previous) + ", " + pathExtractor.apply(value),
                        "duplicate " + kind + " id '" + id + "'");
            }
        }
        return indexed;
    }

    private void setSourcePath(Object value, String sourcePath) {
        // 五类绑定 DTO 各自拥有 sourcePath 字段；这里集中完成绑定后的来源回填。
        if (value instanceof ModelModuleConfig model) model.setSourcePath(sourcePath);
        else if (value instanceof ToolModuleConfig tool) tool.setSourcePath(sourcePath);
        else if (value instanceof SkillModuleConfig skill) skill.setSourcePath(sourcePath);
        else if (value instanceof AgentModuleConfig agent) agent.setSourcePath(sourcePath);
        else if (value instanceof WorkflowModuleConfig workflow) workflow.setSourcePath(sourcePath);
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // 资源索引完整后进入模块语义校验；这里不提前编译，确保引用校验面对完整索引。
        return moduleValidationNode;
    }
}
