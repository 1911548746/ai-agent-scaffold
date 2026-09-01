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
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern SAFE_APP_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

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

        // 所有来源合并后统一检查发布身份和存储策略，确保 Runtime 创建前配置已完全合法。
        validateApplications(applications);
        pipelineContext.getLoadContext().setApplications(List.copyOf(applications));

        // Application 已规范化并索引完成，下一步开始按入口编译可达 Definition。
        return router(command, pipelineContext);
    }

    private void validateApplications(List<AgentDefinitionLoadContext.LocatedApplication> applications) {
        // 分别保存配置 ID 和 ADK appName 的首次位置，错误可以同时指出两个来源。
        Map<String, String> idPaths = new LinkedHashMap<>();
        Map<String, String> appNamePaths = new LinkedHashMap<>();
        for (AgentDefinitionLoadContext.LocatedApplication application : applications) {
            AgentCatalogProperties.ApplicationDefinition definition = application.definition();
            String previous = idPaths.putIfAbsent(definition.getId(), application.sourcePath());
            if (previous != null) {
                throw support.error(application.sourcePath() + ", " + previous,
                        "duplicate application id '" + definition.getId() + "'");
            }

            String appName = definition.getAppName();
            if (!SAFE_APP_NAME.matcher(appName).matches()) {
                throw support.error(application.sourcePath() + ".app-name",
                        "app-name must be a safe path segment of at most 128 characters");
            }
            previous = appNamePaths.putIfAbsent(appName, application.sourcePath());
            if (previous != null) {
                throw support.error(application.sourcePath() + ", " + previous,
                        "duplicate app-name '" + appName + "'");
            }

            validateArtifactStorage(definition.getRunner().getArtifactStorage(), application.sourcePath());
        }
    }

    private void validateArtifactStorage(
            AgentCatalogProperties.ArtifactStorageDefinition storage,
            String applicationPath) {
        String path = applicationPath + ".runner.artifact-storage";
        String type = storage.getType().trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("memory", "minio", "local").contains(type)) {
            throw support.error(path + ".type", "unknown artifact storage type '" + storage.getType() + "'");
        }
        if (storage.getTtl() != null && (storage.getTtl().isZero() || storage.getTtl().isNegative())) {
            throw support.error(path + ".ttl", "artifact storage ttl must be positive");
        }
        if (!"minio".equals(type) && storage.getTtl() != null) {
            throw support.error(path + ".ttl", "ttl is only supported by minio artifact storage");
        }
        if (!"minio".equals(type) && storage.isLifecycleEnabled()) {
            throw support.error(path + ".lifecycle-enabled",
                    "lifecycle-enabled is only supported by minio artifact storage");
        }
        if (storage.isLifecycleEnabled() && storage.getTtl() == null) {
            throw support.error(path + ".lifecycle-enabled", "lifecycle-enabled requires a minio ttl");
        }
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(
            AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // Application 列表已统一来源并完成 ID 去重，下一步可以按根引用进行编译。
        return applicationCompileNode;
    }
}
