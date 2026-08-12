package com.hh.config.agent.catalog;

import com.hh.config.agent.catalog.binding.AgentModuleConfig;
import com.hh.config.agent.catalog.binding.ModelModuleConfig;
import com.hh.config.agent.catalog.binding.ToolModuleConfig;
import com.hh.config.agent.catalog.binding.WorkflowModuleConfig;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.LlmAgentDefinition;
import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.domain.agent.model.enums.AgentWorkflowTypeEnum;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.boot.context.properties.source.ConfigurationPropertySources.from;

/**
 * 将 catalog 和五类模块 YAML 编译为完整 AgentDefinition。
 *
 * <p>编译顺序固定为：读取并绑定资源、Bean Validation、稳定 ID 索引、引用校验、工作流拓扑排序、
 * application 聚合。这样能在创建 ADK Runner 之前报告配置错误，且错误消息只包含路径和 ID。</p>
 */
@Component
public class AgentDefinitionLoader {

    // 新格式尚未提供兼容适配器，版本不一致必须直接失败而不是猜测字段语义。
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final ResourcePatternResolver resourcePatternResolver;
    private final Validator validator;
    private final Environment environment;
    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

    public AgentDefinitionLoader(
            ResourcePatternResolver resourcePatternResolver,
            Validator validator,
            Environment environment) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.validator = validator;
        this.environment = environment;
    }

    /**
     * 从 classpath/file 资源直接加载 catalog，主要供离线编译测试和非 Spring 调用方使用。
     */
    public List<AgentDefinition> load(String catalogLocation) {
        AgentCatalogProperties catalog = bind(
                catalogLocation,
                "ai.agent.config",
                AgentCatalogProperties.class);
        catalog.setCatalogPath(catalogLocation);
        return load(catalog);
    }

    /**
     * 编译已经由 Spring 绑定的 catalog。
     *
     * <p>所有模块会先完整校验，再生成定义列表；不能因为前几个资源可用就发布半成品配置。</p>
     */
    public List<AgentDefinition> load(AgentCatalogProperties catalog) {
        String catalogPath = displayPath(catalog.getCatalogPath(), "ai.agent.config");
        validate(catalog, catalogPath);
        requireSchemaVersion(catalog.getSchemaVersion(), catalogPath + ".schema-version");

        // LinkedHashMap 保留 catalog 的声明顺序，便于生成稳定日志和可复现的测试结果。
        Map<String, ModelModuleConfig> models = index(
                loadModules(catalog.getModelResources(), "ai.agent.model", ModelModuleConfig.class),
                ModelModuleConfig::getId,
                ModelModuleConfig::getSourcePath,
                "model");
        Map<String, ToolModuleConfig> tools = index(
                loadModules(catalog.getToolResources(), "ai.agent.tool", ToolModuleConfig.class),
                ToolModuleConfig::getId,
                ToolModuleConfig::getSourcePath,
                "tool");
        Map<String, AgentModuleConfig> agents = index(
                loadModules(catalog.getAgentResources(), "ai.agent.agent", AgentModuleConfig.class),
                AgentModuleConfig::getId,
                AgentModuleConfig::getSourcePath,
                "agent");
        Map<String, WorkflowModuleConfig> workflows = index(
                loadModules(catalog.getWorkflowResources(), "ai.agent.workflow", WorkflowModuleConfig.class),
                WorkflowModuleConfig::getId,
                WorkflowModuleConfig::getSourcePath,
                "workflow");

        // ID 表完整后再校验跨文件引用，避免把“文件加载顺序”误当作依赖关系。
        validateModules(models, tools, agents, workflows);
        // 子工作流必须先于父工作流交给旧 Armory 创建，否则运行时会得到空子节点。
        List<String> workflowOrder = topologicalSort(workflows);
        List<LocatedApplication> applications = loadApplications(catalog, catalogPath);
        validateApplicationIds(applications);

        List<AgentDefinition> definitions = new ArrayList<>();
        for (LocatedApplication application : applications) {
            definitions.add(compileApplication(
                    application.definition(),
                    application.sourcePath(),
                    models,
                    tools,
                    agents,
                    workflows,
                    workflowOrder));
        }
        return List.copyOf(definitions);
    }

    /**
     * 校验每类模块自身的 schema，并验证所有跨模块稳定引用。
     */
    private void validateModules(
            Map<String, ModelModuleConfig> models,
            Map<String, ToolModuleConfig> tools,
            Map<String, AgentModuleConfig> agents,
            Map<String, WorkflowModuleConfig> workflows) {
        models.values().forEach(model -> {
            requireSchemaVersion(model.getSchemaVersion(), model.getSourcePath() + ".schema-version");
            // 占位符在进入 ADK Builder 前解析，缺失环境变量能定位到 YAML 字段而不是远端请求失败。
            model.setBaseUrl(resolveRequired(model.getBaseUrl(), model.getSourcePath() + ".base-url"));
            model.setApiKey(resolveRequired(model.getApiKey(), model.getSourcePath() + ".api-key"));
            model.setModelName(resolveRequired(model.getModelName(), model.getSourcePath() + ".model-name"));
        });

        tools.values().forEach(tool -> {
            requireSchemaVersion(tool.getSchemaVersion(), tool.getSourcePath() + ".schema-version");
            validateTool(tool);
        });

        agents.values().forEach(agent -> {
            requireSchemaVersion(agent.getSchemaVersion(), agent.getSourcePath() + ".schema-version");
            if (!models.containsKey(agent.getModelRef())) {
                throw error(agent.getSourcePath() + ".model-ref", "unknown model id '" + agent.getModelRef() + "'");
            }
            for (int i = 0; i < agent.getToolRefs().size(); i++) {
                String toolRef = agent.getToolRefs().get(i);
                if (!tools.containsKey(toolRef)) {
                    throw error(agent.getSourcePath() + ".tool-refs[" + i + "]", "unknown tool id '" + toolRef + "'");
                }
            }
        });

        workflows.values().forEach(workflow -> {
            requireSchemaVersion(workflow.getSchemaVersion(), workflow.getSourcePath() + ".schema-version");
            if (AgentWorkflowTypeEnum.getByType(workflow.getType()) == null) {
                throw error(workflow.getSourcePath() + ".type", "unsupported workflow type '" + workflow.getType() + "'");
            }
            for (int i = 0; i < workflow.getSubAgentRefs().size(); i++) {
                String ref = workflow.getSubAgentRefs().get(i);
                if (!agents.containsKey(ref)) {
                    throw error(workflow.getSourcePath() + ".sub-agent-refs[" + i + "]", "unknown agent id '" + ref + "'");
                }
            }
            for (int i = 0; i < workflow.getSubWorkflowRefs().size(); i++) {
                String ref = workflow.getSubWorkflowRefs().get(i);
                if (!workflows.containsKey(ref)) {
                    throw error(workflow.getSourcePath() + ".sub-workflow-refs[" + i + "]", "unknown workflow id '" + ref + "'");
                }
            }
            if (workflow.getSubAgentRefs().isEmpty() && workflow.getSubWorkflowRefs().isEmpty()) {
                throw error(workflow.getSourcePath(), "workflow must reference at least one agent or workflow");
            }
        });
    }

    /**
     * 把 tool.type 与实际内容交叉校验，阻止一个 MCP 配置同时携带多个 transport 后被 if 顺序静默选择。
     */
    private void validateTool(ToolModuleConfig tool) {
        String type = tool.getType().toLowerCase(Locale.ROOT);
        if ("skill".equals(type)) {
            if (tool.getSkill() == null || tool.getMcp() != null) {
                throw error(tool.getSourcePath(), "skill tool must configure only skill.directory");
            }
            return;
        }
        if (!"mcp".equals(type)) {
            throw error(tool.getSourcePath() + ".type", "unsupported tool type '" + tool.getType() + "'");
        }
        if (tool.getMcp() == null || tool.getSkill() != null) {
            throw error(tool.getSourcePath(), "MCP tool must configure exactly one transport");
        }
        ToolModuleConfig.McpDefinition mcp = tool.getMcp();
        int transportCount = countPresent(mcp.getSse(), mcp.getHttp(), mcp.getStdio(), mcp.getLocal());
        if (transportCount != 1) {
            throw error(tool.getSourcePath() + ".mcp", "MCP tool must configure exactly one transport");
        }
    }

    /**
     * 统计已配置的 transport；使用对象存在性而不是字段值，确保空对象同样会触发后续 Bean Validation。
     */
    private int countPresent(Object... values) {
        int count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 从一个对外 application 入口收集其可达的 Agent/Workflow，并适配为旧装配链所需的完整定义。
     */
    private AgentDefinition compileApplication(
            AgentCatalogProperties.ApplicationDefinition application,
            String applicationPath,
            Map<String, ModelModuleConfig> models,
            Map<String, ToolModuleConfig> tools,
            Map<String, AgentModuleConfig> agents,
            Map<String, WorkflowModuleConfig> workflows,
            List<String> workflowOrder) {
        boolean hasAgentRoot = hasText(application.getAgentRef());
        boolean hasWorkflowRoot = hasText(application.getWorkflowRef());
        // 根节点必须二选一；同时缺失或同时存在都会让 Runner 的 rootAgent 语义不明确。
        if (hasAgentRoot == hasWorkflowRoot) {
            throw error(applicationPath + ".root", "exactly one of agent-ref or workflow-ref is required");
        }
        if (hasAgentRoot && !agents.containsKey(application.getAgentRef())) {
            throw error(applicationPath + ".agent-ref", "unknown root agent id '" + application.getAgentRef() + "'");
        }
        if (hasWorkflowRoot && !workflows.containsKey(application.getWorkflowRef())) {
            throw error(applicationPath + ".workflow-ref", "unknown root workflow id '" + application.getWorkflowRef() + "'");
        }

        // LinkedHashSet 去重但不打乱 YAML 中的子节点顺序，顺序对 sequential workflow 有业务含义。
        LinkedHashSet<String> reachableAgents = new LinkedHashSet<>();
        LinkedHashSet<String> reachableWorkflows = new LinkedHashSet<>();
        if (hasAgentRoot) {
            reachableAgents.add(application.getAgentRef());
        } else {
            collectWorkflow(application.getWorkflowRef(), workflows, reachableAgents, reachableWorkflows);
        }
        if (reachableAgents.isEmpty()) {
            throw error(applicationPath + ".root", "root does not resolve to an LLM agent");
        }

        LinkedHashSet<String> modelRefs = reachableAgents.stream()
                .map(agents::get)
                .map(AgentModuleConfig::getModelRef)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // 当前装配模型只有一个 ChatModel，跨模型工作流需要 Provider SPI 后再支持。
        if (modelRefs.size() != 1) {
            throw error(applicationPath + ".root", "current ADK assembly requires all referenced agents to use one model-ref");
        }

        List<LlmAgentDefinition> compiledAgents = reachableAgents.stream()
                .map(agents::get)
                .map(agent -> compileAgent(agent, tools))
                .toList();
        // 使用全局拓扑序过滤可达工作流，保证父工作流引用其子工作流时子节点已经创建。
        List<AgentWorkflowDefinition> compiledWorkflows = workflowOrder.stream()
                .filter(reachableWorkflows::contains)
                .map(workflows::get)
                .map(workflow -> compileWorkflow(workflow, agents, workflows))
                .toList();

        String rootAgentName = hasAgentRoot
                ? agents.get(application.getAgentRef()).getName()
                : workflows.get(application.getWorkflowRef()).getName();
        ModelModuleConfig model = models.get(modelRefs.iterator().next());

        return AgentDefinition.builder()
                .id(application.getId())
                .enabled(application.isEnabled())
                .sourcePath(applicationPath)
                .appName(application.getAppName())
                .agentDescriptor(toAgentDescriptor(application))
                .rootAgentName(rootAgentName)
                .chatModel(toChatModel(model))
                .agents(List.copyOf(compiledAgents))
                .agentWorkflows(List.copyOf(compiledWorkflows))
                .runner(toRunner(application.getRunner(), rootAgentName))
                .build();
    }

    /**
     * 深度收集 root workflow 可达节点。循环已在拓扑排序阶段统一检测，这里只处理合法 DAG 的去重遍历。
     */
    private void collectWorkflow(
            String workflowId,
            Map<String, WorkflowModuleConfig> workflows,
            LinkedHashSet<String> agentIds,
            LinkedHashSet<String> workflowIds) {
        if (!workflowIds.add(workflowId)) {
            // 同一个子工作流可被多个父节点引用，只需收集一次。
            return;
        }
        WorkflowModuleConfig workflow = workflows.get(workflowId);
        agentIds.addAll(workflow.getSubAgentRefs());
        workflow.getSubWorkflowRefs().forEach(ref -> collectWorkflow(ref, workflows, agentIds, workflowIds));
    }

    /**
     * 将稳定的 tool-ref 列表转换为 LLM Agent 定义的 MCP 列表和单个 Skill 目录字段。
     */
    private LlmAgentDefinition compileAgent(AgentModuleConfig agent, Map<String, ToolModuleConfig> tools) {
        LlmAgentDefinition result = new LlmAgentDefinition();
        result.setName(agent.getName());
        result.setDescription(agent.getDescription());
        result.setInstruction(agent.getInstruction());
        result.setOutputKey(agent.getOutputKey());

        List<McpToolDefinition> mcpTools = new ArrayList<>();
        String skillsDirectory = null;
        for (int i = 0; i < agent.getToolRefs().size(); i++) {
            ToolModuleConfig tool = tools.get(agent.getToolRefs().get(i));
            if ("skill".equalsIgnoreCase(tool.getType())) {
                // 当前定义只有 skillsDir 一个字段，多个 Skill 目录不能被悄悄丢弃。
                if (skillsDirectory != null) {
                    throw error(agent.getSourcePath() + ".tool-refs[" + i + "]", "current ADK assembly supports one skill directory per agent");
                }
                skillsDirectory = resolveRequired(tool.getSkill().getDirectory(), tool.getSourcePath() + ".skill.directory");
            } else {
                mcpTools.add(toMcpTool(tool));
            }
        }
        result.setMcpToolList(mcpTools);
        result.setSkillsDir(skillsDirectory);
        return result;
    }

    /**
     * 将已经通过 transport 互斥校验的工具映射到现有 MCP 工厂使用的值对象。
     */
    private McpToolDefinition toMcpTool(ToolModuleConfig tool) {
        McpToolDefinition result = new McpToolDefinition();
        ToolModuleConfig.McpDefinition source = tool.getMcp();
        if (source.getSse() != null) {
            McpToolDefinition.SSEServerParameters target = new McpToolDefinition.SSEServerParameters();
            target.setName(source.getSse().getName());
            target.setUrl(resolveRequired(source.getSse().getUrl(), tool.getSourcePath() + ".mcp.sse.url"));
            target.setSseEndpoint(source.getSse().getSseEndpoint());
            target.setTimeout(source.getSse().getTimeout());
            target.setRequestTimeout(source.getSse().getRequestTimeout());
            result.setSse(target);
        }
        if (source.getHttp() != null) {
            McpToolDefinition.HttpServerParameters target = new McpToolDefinition.HttpServerParameters();
            target.setName(source.getHttp().getName());
            target.setUrl(resolveRequired(source.getHttp().getUrl(), tool.getSourcePath() + ".mcp.http.url"));
            Map<String, String> headers = new LinkedHashMap<>();
            source.getHttp().getHeaders().forEach((name, value) -> headers.put(
                    name,
                    // 只把通用字段路径传给错误消息，避免 header 名或值意外写进日志。
                    resolveRequired(value, tool.getSourcePath() + ".mcp.http.headers")));
            target.setHeaders(headers);
            target.setTimeout(source.getHttp().getTimeout());
            target.setRequestTimeout(source.getHttp().getRequestTimeout());
            result.setHttp(target);
        }
        if (source.getStdio() != null) {
            McpToolDefinition.StdioServerParameters target = new McpToolDefinition.StdioServerParameters();
            target.setName(source.getStdio().getName());
            target.setCommand(resolveRequired(source.getStdio().getCommand(), tool.getSourcePath() + ".mcp.stdio.command"));
            target.setArgs(new ArrayList<>(source.getStdio().getArgs()));
            Map<String, String> env = new LinkedHashMap<>();
            source.getStdio().getEnv().forEach((name, value) -> env.put(
                    name,
                    resolveRequired(value, tool.getSourcePath() + ".mcp.stdio.env." + name)));
            target.setEnv(env);
            result.setStdio(target);
        }
        if (source.getLocal() != null) {
            McpToolDefinition.LocalServerParameters target = new McpToolDefinition.LocalServerParameters();
            target.setBeanName(source.getLocal().getBeanName());
            target.setMethodName(source.getLocal().getMethodName());
            result.setLocal(target);
        }
        return result;
    }

    /**
     * 将工作流稳定 ID 引用转换为 ADK 装配识别的运行时 agent name。
     */
    private AgentWorkflowDefinition compileWorkflow(
            WorkflowModuleConfig workflow,
            Map<String, AgentModuleConfig> agents,
            Map<String, WorkflowModuleConfig> workflows) {
        AgentWorkflowDefinition result = new AgentWorkflowDefinition();
        result.setType(workflow.getType());
        result.setName(workflow.getName());
        result.setDescription(workflow.getDescription());
        result.setInstruction(workflow.getInstruction());
        result.setMaxIterations(workflow.getMaxIterations());
        List<String> children = new ArrayList<>();
        // 当前工作流定义只提供一个 subAgents 字段，因此在这里合并两类已解析子节点。
        workflow.getSubAgentRefs().stream().map(agents::get).map(AgentModuleConfig::getName).forEach(children::add);
        workflow.getSubWorkflowRefs().stream().map(workflows::get).map(WorkflowModuleConfig::getName).forEach(children::add);
        result.setSubAgents(children);
        return result;
    }

    /**
     * 对外展示信息只来自 catalog application，不从内部 Agent 名称反推，避免 API 契约被工作流重命名影响。
     */
    private AgentDescriptor toAgentDescriptor(AgentCatalogProperties.ApplicationDefinition source) {
        AgentDescriptor result = new AgentDescriptor();
        result.setAgentId(source.getAgentId());
        result.setAgentName(source.getAgentName());
        result.setAgentDesc(source.getAgentDesc());
        return result;
    }

    /**
     * 复制已解析的模型参数；apiKey 只在内存中传递，任何调用方都不得将该对象整体记录到日志。
     */
    private ChatModelDefinition toChatModel(ModelModuleConfig source) {
        ChatModelDefinition result = new ChatModelDefinition();
        result.setBaseUrl(source.getBaseUrl());
        result.setApiKey(source.getApiKey());
        result.setModelName(source.getModelName());
        result.setTimeout(source.getTimeout());
        result.setMaxRetries(source.getMaxRetries());
        result.setStreamingFirstResponseTimeout(source.getStreamingFirstResponseTimeout());
        result.setStreamingMaxRetries(source.getStreamingMaxRetries());
        return result;
    }

    /**
     * 将 catalog Runner 配置转为运行定义，并以解析后的 root 名称覆盖 YAML 输入。
     */
    private RunnerDefinition toRunner(AgentCatalogProperties.RunnerDefinition source, String rootAgentName) {
        RunnerDefinition result = new RunnerDefinition();
        result.setAgentName(rootAgentName);
        result.setPersistence(source.isPersistence());
        result.setGroupFunctionResponsesInHistory(source.isGroupFunctionResponsesInHistory());
        result.setPluginNameList(new ArrayList<>(source.getPluginNameList()));

        // 显式复制全部压缩参数，避免迁移后因 null 或默认值差异改变上下文保留策略。
        AgentCatalogProperties.ContextCompressDefinition compression = source.getContextCompress();
        ContextCompressionDefinition targetCompression = new ContextCompressionDefinition();
        targetCompression.setType(compression.getType());
        targetCompression.setCompactionInterval(compression.getCompactionInterval());
        targetCompression.setOverlapSize(compression.getOverlapSize());
        targetCompression.setTokenThreshold(compression.getTokenThreshold());
        targetCompression.setEventRetentionSize(compression.getEventRetentionSize());
        targetCompression.setInvocationRetentionSize(compression.getInvocationRetentionSize());
        result.setContextCompress(targetCompression);
        return result;
    }

    /**
     * 对工作流依赖图进行 DFS 拓扑排序。
     *
     * <p>返回顺序保证子工作流位于父工作流之前；发现回边时立即构造完整循环路径。</p>
     */
    private List<String> topologicalSort(Map<String, WorkflowModuleConfig> workflows) {
        Map<String, VisitState> states = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        workflows.keySet().forEach(id -> visitWorkflow(id, workflows, states, path, order));
        return order;
    }

    /**
     * DFS 的三色访问步骤：VISITING 表示当前递归路径，VISITED 表示该节点已可安全复用。
     */
    private void visitWorkflow(
            String id,
            Map<String, WorkflowModuleConfig> workflows,
            Map<String, VisitState> states,
            Deque<String> path,
            List<String> order) {
        VisitState state = states.get(id);
        if (state == VisitState.VISITED) {
            return;
        }
        if (state == VisitState.VISITING) {
            // 从首次出现循环节点的位置截取，错误消息只展示真正的环而非整条遍历路径。
            List<String> currentPath = new ArrayList<>(path);
            int cycleStart = currentPath.indexOf(id);
            List<String> cycle = new ArrayList<>(currentPath.subList(cycleStart, currentPath.size()));
            cycle.add(id);
            String resources = cycle.stream()
                    .map(workflows::get)
                    .map(WorkflowModuleConfig::getSourcePath)
                    .distinct()
                    .collect(Collectors.joining(", "));
            throw error(resources, "workflow cycle detected: " + String.join(" -> ", cycle));
        }

        states.put(id, VisitState.VISITING);
        path.addLast(id);
        for (String child : workflows.get(id).getSubWorkflowRefs()) {
            visitWorkflow(child, workflows, states, path, order);
        }
        path.removeLast();
        states.put(id, VisitState.VISITED);
        // 后序加入列表，保证所有子工作流都先于当前工作流创建。
        order.add(id);
    }

    /**
     * 校验 catalog application 的稳定 ID 唯一，避免两个对外入口覆盖同一个运行时表项。
     */
    private void validateApplicationIds(List<LocatedApplication> applications) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (LocatedApplication application : applications) {
            String id = application.definition().getId();
            String path = application.sourcePath();
            String previous = paths.putIfAbsent(id, path);
            if (previous != null) {
                throw error(path + ", " + previous, "duplicate application id '" + id + "'");
            }
        }
    }

    /**
     * 将同类资源建立为 ID 索引，并在重复时同时报告两处来源路径。
     */
    private <T> Map<String, T> index(
            List<T> values,
            Function<T, String> idExtractor,
            Function<T, String> pathExtractor,
            String kind) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : values) {
            String id = idExtractor.apply(value);
            T previous = indexed.putIfAbsent(id, value);
            if (previous != null) {
                throw error(
                        pathExtractor.apply(previous) + ", " + pathExtractor.apply(value),
                        "duplicate " + kind + " id '" + id + "'");
            }
        }
        return indexed;
    }

    /**
     * 按 catalog 声明顺序展开资源模式，再以稳定路径排序同一模式下的结果。
     *
     * <p>同一物理文件被多个模式命中时只加载一次；不同文件的重复模块 ID 仍由 {@link #index}
     * 负责拒绝，不能因自动发现掩盖真实配置冲突。</p>
     */
    private <T> List<T> loadModules(List<String> locations, String prefix, Class<T> type) {
        List<T> values = new ArrayList<>();
        for (LocatedResource locatedResource : resolveModuleResources(locations)) {
            T value = bind(locatedResource.resource(), locatedResource.sourcePath(), prefix, type);
            setSourcePath(value, locatedResource.sourcePath());
            values.add(value);
        }
        return values;
    }

    /**
     * 合并兼容的内联 application 与模块化 application YAML。
     *
     * <p>内联项先于自动发现项，确保已有外部 catalog 在迁移前后的发布顺序不变；自动发现项则由
     * {@link #resolveModuleResources(List)} 按稳定路径排序。</p>
     */
    private List<LocatedApplication> loadApplications(AgentCatalogProperties catalog, String catalogPath) {
        List<LocatedApplication> applications = new ArrayList<>();
        for (int i = 0; i < catalog.getApplications().size(); i++) {
            applications.add(new LocatedApplication(
                    catalog.getApplications().get(i),
                    catalogPath + ".applications[" + i + "]"));
        }
        for (LocatedResource resource : resolveModuleResources(catalog.getApplicationResources())) {
            AgentCatalogProperties.ApplicationDefinition application = bind(
                    resource.resource(), resource.sourcePath(), "ai.agent.application",
                    AgentCatalogProperties.ApplicationDefinition.class);
            applications.add(new LocatedApplication(application, resource.sourcePath()));
        }
        return List.copyOf(applications);
    }

    /**
     * 将精确资源地址或 Ant 通配地址展开为实际资源。
     *
     * <p>默认 catalog 只允许 {@code classpath:}，避免 {@code classpath*:} 将依赖 JAR 的同路径 YAML
     * 意外纳入当前应用配置。</p>
     */
    private List<LocatedResource> resolveModuleResources(List<String> locations) {
        Map<String, LocatedResource> resourcesByIdentity = new LinkedHashMap<>();
        for (String location : locations) {
            if (location.startsWith(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX)) {
                throw error(location, "classpath*: resource scanning is not supported");
            }

            Resource[] resources;
            try {
                resources = resourcePatternResolver.getResources(location);
            } catch (IOException exception) {
                // Spring 在通配根目录不存在时会抛 IOException；对用户而言它仍是“没有匹配文件”。
                if (isPattern(location)) {
                    throw error(location, "configuration resource pattern does not match any files");
                }
                throw new AgentConfigurationException(location + ": unable to resolve configuration resource");
            }
            if (resources.length == 0) {
                String message = isPattern(location)
                        ? "configuration resource pattern does not match any files"
                        : "configuration resource does not exist";
                throw error(location, message);
            }

            // 文件系统与 JAR 返回的枚举顺序不应影响编译日志、错误路径或测试结果。
            Arrays.sort(resources, Comparator.comparing(this::resourceIdentity));
            for (Resource resource : resources) {
                if (!resource.exists()) {
                    throw error(location, "configuration resource does not exist");
                }
                String identity = resourceIdentity(resource);
                String sourcePath = isPattern(location) ? resource.getDescription() : location;
                resourcesByIdentity.putIfAbsent(identity, new LocatedResource(resource, sourcePath));
            }
        }
        return List.copyOf(resourcesByIdentity.values());
    }

    private boolean isPattern(String location) {
        return location.indexOf('*') >= 0 || location.indexOf('?') >= 0;
    }

    private String resourceIdentity(Resource resource) {
        try {
            return resource.getURI().normalize().toString();
        } catch (IOException exception) {
            // URI 不可用时退回描述文本；它仍足以避免同一次扫描中的重复加载。
            return resource.getDescription();
        }
    }

    /**
     * 绑定 DTO 不把 sourcePath 暴露给 YAML；Loader 在此回填它，供后续错误定位使用。
     */
    private void setSourcePath(Object value, String sourcePath) {
        if (value instanceof ModelModuleConfig model) {
            model.setSourcePath(sourcePath);
        } else if (value instanceof ToolModuleConfig tool) {
            tool.setSourcePath(sourcePath);
        } else if (value instanceof AgentModuleConfig agent) {
            agent.setSourcePath(sourcePath);
        } else if (value instanceof WorkflowModuleConfig workflow) {
            workflow.setSourcePath(sourcePath);
        }
    }

    /**
     * 从单个 YAML Resource 绑定指定前缀并执行 Bean Validation。
     *
     * <p>所有底层绑定异常都会被转换为不包含原始属性值的配置异常，防止 apiKey、header 等敏感值
     * 随异常消息泄漏。</p>
     */
    private <T> T bind(String location, String prefix, Class<T> type) {
        Resource resource = resourcePatternResolver.getResource(location);
        if (!resource.exists()) {
            throw error(location, "configuration resource does not exist");
        }
        return bind(resource, location, prefix, type);
    }

    private <T> T bind(Resource resource, String sourcePath, String prefix, Class<T> type) {
        try {
            MutablePropertySources sources = new MutablePropertySources();
            List<PropertySource<?>> loaded = yamlLoader.load(sourcePath, resource);
            loaded.forEach(sources::addLast);
            // 每个文件独立 Binder，确保“每份 YAML 只描述一个对象”不会污染全局 Spring Environment。
            T value = new Binder(from(sources))
                    .bind(prefix, Bindable.of(type))
                    .orElseThrow(() -> error(sourcePath, "missing configuration prefix '" + prefix + "'"));
            validate(value, sourcePath);
            return value;
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (BindException exception) {
            // BindException 只保留属性名，不能透传其原始消息中的 rejected value。
            throw new AgentConfigurationException(
                    sourcePath + "." + exception.getName() + ": configuration binding failed");
        } catch (IOException exception) {
            throw new AgentConfigurationException(sourcePath + ": unable to read configuration resource");
        } catch (RuntimeException exception) {
            throw new AgentConfigurationException(sourcePath + ": configuration binding failed for '" + prefix + "'");
        }
    }

    private record LocatedResource(Resource resource, String sourcePath) {
    }

    private record LocatedApplication(
            AgentCatalogProperties.ApplicationDefinition definition,
            String sourcePath) {
    }

    /**
     * 统一格式化 Bean Validation 失败信息，使错误同时包含资源和字段路径。
     */
    private <T> void validate(T value, String sourcePath) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (violations.isEmpty()) {
            return;
        }
        String details = violations.stream()
                .sorted((left, right) -> left.getPropertyPath().toString().compareTo(right.getPropertyPath().toString()))
                .map(violation -> sourcePath + "." + violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining("; "));
        throw new AgentConfigurationException(details);
    }

    /**
     * 解析环境变量占位符并拒绝空结果。
     *
     * <p>失败时只报告 YAML 路径，不输出占位符解析前后的敏感文本。</p>
     */
    private String resolveRequired(String value, String path) {
        try {
            String resolved = environment.resolveRequiredPlaceholders(value);
            if (!hasText(resolved)) {
                throw error(path, "resolved value must not be blank");
            }
            return resolved;
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentConfigurationException(path + ": required environment placeholder is not configured");
        }
    }

    /**
     * 当前编译器只理解一个 schema 版本，避免结构升级被悄悄按旧规则装配。
     */
    private void requireSchemaVersion(Integer version, String path) {
        if (version == null || version != SUPPORTED_SCHEMA_VERSION) {
            throw error(path, "unsupported schema-version; expected " + SUPPORTED_SCHEMA_VERSION);
        }
    }

    /**
     * catalog 尚未记录自身路径时使用配置前缀兜底，保证每个错误仍有可定位位置。
     */
    private String displayPath(String configuredPath, String fallback) {
        return hasText(configuredPath) ? configuredPath : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 统一配置异常格式，调用方只能得到路径和公开原因。
     */
    private AgentConfigurationException error(String path, String message) {
        return new AgentConfigurationException(path + ": " + message);
    }

    /**
     * 工作流 DFS 的访问状态，用于区分合法复用节点与循环依赖。
     */
    private enum VisitState {
        VISITING,
        VISITED
    }
}
