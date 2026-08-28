package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.resource.CatalogResourceBinder;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadContext;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application 入口绑定节点。
 *
 * <p>前置条件是 model、tool、skill、agent 和 workflow 的静态校验已经完成。本节点合并兼容的
 * catalog 内联 application 与自动发现的 application YAML，并只校验 application 自身 ID 唯一性。</p>
 *
 * <p>内联项保持在自动发现项之前，以维持历史发布顺序；资源绑定委托给共享资源访问器，节点本身不
 * 解释 Agent 根节点或 Runner 字段。</p>
 */
@Component
public class ApplicationBindingNode extends AgentDefinitionLoadNode {

    private final CatalogResourceBinder resourceBinder;
    private final AgentDefinitionConfigSupport support;
    private final ApplicationCompileNode applicationCompileNode;

    public ApplicationBindingNode(
            CatalogResourceBinder resourceBinder,
            AgentDefinitionConfigSupport support,
            ApplicationCompileNode applicationCompileNode) {
        this.resourceBinder = resourceBinder;
        this.support = support;
        this.applicationCompileNode = applicationCompileNode;
    }

    @Override
    protected List<AgentDefinition> doApply(
            AgentDefinitionLoadCommand command,
            AgentDefinitionLoadPipelineContext pipelineContext) throws Exception {
        // 先创建中间列表，内联 application 和文件 application 在此阶段统一成同一种上下文对象。
        var catalog = command.getCatalog();
        List<AgentDefinitionLoadContext.LocatedApplication> applications = new ArrayList<>();
        for (int i = 0; i < catalog.getApplications().size(); i++) {
            // 内联 application 没有真实文件路径，使用 catalog 数组下标构造稳定错误路径。
            applications.add(new AgentDefinitionLoadContext.LocatedApplication(
                    catalog.getApplications().get(i),
                    pipelineContext.getCatalogPath() + ".applications[" + i + "]"));
        }

        // 自动发现 application 文件追加在内联配置之后，保持既有加载优先级和输出顺序。
        for (CatalogResourceBinder.LocatedResource resource : resourceBinder.resolve(catalog.getApplicationResources())) {
            // 每个 application 文件使用独立前缀绑定，并由 Binder 完成字段级校验。
            AgentCatalogProperties.ApplicationDefinition application = resourceBinder.bind(
                    resource, "ai.agent.application", AgentCatalogProperties.ApplicationDefinition.class);
            applications.add(new AgentDefinitionLoadContext.LocatedApplication(application, resource.sourcePath()));
        }

        // 所有来源合并后统一检查 ID，避免同一 application 被重复发布。
        validateApplicationIds(applications);
        pipelineContext.getLoadContext().setApplications(List.copyOf(applications));

        // Application 已规范化并索引完成，下一步开始按入口编译可达 Definition。
        return router(command, pipelineContext);
    }

    private void validateApplicationIds(List<AgentDefinitionLoadContext.LocatedApplication> applications) {
        // 保存 ID 首次出现的位置，重复项可以一次性给出两个配置来源。
        Map<String, String> paths = new LinkedHashMap<>();
        for (AgentDefinitionLoadContext.LocatedApplication application : applications) {
            String previous = paths.putIfAbsent(application.definition().getId(), application.sourcePath());
            if (previous != null) {
                throw support.error(application.sourcePath() + ", " + previous,
                        "duplicate application id '" + application.definition().getId() + "'");
            }
        }
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // Application 列表已统一来源并完成 ID 去重，下一步可以按根引用进行编译。
        return applicationCompileNode;
    }
}
