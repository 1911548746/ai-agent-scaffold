package com.hh.config.agent;

import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.legacyconfig.AgentInfo;
import com.hh.domain.agent.model.legacyconfig.AgentWorkflow;
import com.hh.domain.agent.model.legacyconfig.ChatModel;
import com.hh.domain.agent.model.legacyconfig.LlmAgent;
import com.hh.domain.agent.model.legacyconfig.McpTool;
import com.hh.domain.agent.model.legacyconfig.Module;
import com.hh.domain.agent.model.enums.AgentWorkflowTypeEnum;
import com.hh.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
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
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
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
 * 将 catalog 和四类模块 YAML 编译为完整 AgentDefinition。
 *
 * <p>编译顺序固定为：读取并绑定资源、Bean Validation、稳定 ID 索引、引用校验、工作流拓扑排序、
 * application 聚合。这样能在创建 ADK Runner 之前报告配置错误，且错误消息只包含路径和 ID。</p>
 */
@Component
public class AgentDefinitionLoader {

    // 新格式尚未提供兼容适配器，版本不一致必须直接失败而不是猜测字段语义。
    private static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final ResourceLoader resourceLoader;
    private final Validator validator;
    private final Environment environment;
    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

    public AgentDefinitionLoader(ResourceLoader resourceLoader, Validator validator, Environment environment) {
        this.resourceLoader = resourceLoader;
        this.validator = validator;
        this.environment = environment;
    }

    /**
     * 从 classpath/file 资源直接加载 catalog，主要供离线编译测试和非 Spring 调用方使用。
     */
    public List<AgentDefinition> load(String catalogLocation) {
        AiAgentAutoConfigProperties catalog = bind(
                catalogLocation,
                "ai.agent.config",
                AiAgentAutoConfigProperties.class);
        catalog.setCatalogPath(catalogLocation);
        return load(catalog);
    }

    /**
     * 编译已经由 Spring 绑定的 catalog。
     *
     * <p>所有模块会先完整校验，再生成定义列表；不能因为前几个资源可用就发布半成品配置。</p>
     */
    public List<AgentDefinition> load(AiAgentAutoConfigProperties catalog) {
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
        validateApplicationIds(catalog, catalogPath);

        List<AgentDefinition> definitions = new ArrayList<>();
        for (int i = 0; i < catalog.getApplications().size(); i++) {
            // application 下标写入错误路径，用户无需从整份 catalog 猜测失败条目。
            definitions.add(compileApplication(
                    catalog.getApplications().get(i),
                    catalogPath + ".applications[" + i + "]",
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
            AiAgentAutoConfigProperties.ApplicationDefinition application,
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

        List<LlmAgent> compiledAgents = reachableAgents.stream()
                .map(agents::get)
                .map(agent -> compileAgent(agent, tools))
                .toList();
        // 使用全局拓扑序过滤可达工作流，保证父工作流引用其子工作流时子节点已经创建。
        List<AgentWorkflow> compiledWorkflows = workflowOrder.stream()
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
                .agentInfo(toAgentInfo(application))
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
     * 将稳定的 tool-ref 列表转换为旧 LlmAgent 的 MCP 列表和单个 Skill 目录字段。
     */
    private LlmAgent compileAgent(AgentModuleConfig agent, Map<String, ToolModuleConfig> tools) {
        LlmAgent result = new LlmAgent();
        result.setName(agent.getName());
        result.setDescription(agent.getDescription());
        result.setInstruction(agent.getInstruction());
        result.setOutputKey(agent.getOutputKey());

        List<McpTool> mcpTools = new ArrayList<>();
        String skillsDirectory = null;
        for (int i = 0; i < agent.getToolRefs().size(); i++) {
            ToolModuleConfig tool = tools.get(agent.getToolRefs().get(i));
            if ("skill".equalsIgnoreCase(tool.getType())) {
                // 旧模型只有 skillsDir 一个字段，多个 Skill 目录不能被悄悄丢弃。
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
    private McpTool toMcpTool(ToolModuleConfig tool) {
        McpTool result = new McpTool();
        ToolModuleConfig.McpDefinition source = tool.getMcp();
        if (source.getSse() != null) {
            McpTool.SSEServerParameters target = new McpTool.SSEServerParameters();
            target.setName(source.getSse().getName());
            target.setUrl(resolveRequired(source.getSse().getUrl(), tool.getSourcePath() + ".mcp.sse.url"));
            target.setSseEndpoint(source.getSse().getSseEndpoint());
            target.setTimeout(source.getSse().getTimeout());
            target.setRequestTimeout(source.getSse().getRequestTimeout());
            result.setSse(target);
        }
        if (source.getHttp() != null) {
            McpTool.HttpServerParameters target = new McpTool.HttpServerParameters();
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
            McpTool.StdioServerParameters target = new McpTool.StdioServerParameters();
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
            McpTool.LocalServerParameters target = new McpTool.LocalServerParameters();
            target.setBeanName(source.getLocal().getBeanName());
            target.setMethodName(source.getLocal().getMethodName());
            result.setLocal(target);
        }
        return result;
    }

    /**
     * 将工作流稳定 ID 引用转换为 ADK/旧策略树识别的运行时 agent name。
     */
    private AgentWorkflow compileWorkflow(
            WorkflowModuleConfig workflow,
            Map<String, AgentModuleConfig> agents,
            Map<String, WorkflowModuleConfig> workflows) {
        AgentWorkflow result = new AgentWorkflow();
        result.setType(workflow.getType());
        result.setName(workflow.getName());
        result.setDescription(workflow.getDescription());
        result.setInstruction(workflow.getInstruction());
        result.setMaxIterations(workflow.getMaxIterations());
        List<String> children = new ArrayList<>();
        // 旧 AgentWorkflow 只提供一个 subAgents 字段，因此在这里合并两类已解析子节点。
        workflow.getSubAgentRefs().stream().map(agents::get).map(AgentModuleConfig::getName).forEach(children::add);
        workflow.getSubWorkflowRefs().stream().map(workflows::get).map(WorkflowModuleConfig::getName).forEach(children::add);
        result.setSubAgents(children);
        return result;
    }

    /**
     * 对外展示信息只来自 catalog application，不从内部 Agent 名称反推，避免 API 契约被工作流重命名影响。
     */
    private AgentInfo toAgentInfo(AiAgentAutoConfigProperties.ApplicationDefinition source) {
        AgentInfo result = new AgentInfo();
        result.setAgentId(source.getAgentId());
        result.setAgentName(source.getAgentName());
        result.setAgentDesc(source.getAgentDesc());
        return result;
    }

    /**
     * 复制已解析的模型参数；apiKey 只在内存中传递，任何调用方都不得将该对象整体记录到日志。
     */
    private ChatModel toChatModel(ModelModuleConfig source) {
        ChatModel result = new ChatModel();
        result.setBaseUrl(source.getBaseUrl());
        result.setApiKey(source.getApiKey());
        result.setModelName(source.getModelName());
        result.setTimeout(source.getTimeout());
        result.setMaxRetries(source.getMaxRetries());
        return result;
    }

    /**
     * 将 catalog Runner 配置转为旧 Module.Runner，并以解析后的 root 名称覆盖 YAML 输入。
     */
    private Module.Runner toRunner(AiAgentAutoConfigProperties.RunnerDefinition source, String rootAgentName) {
        Module.Runner result = new Module.Runner();
        result.setAgentName(rootAgentName);
        result.setPersistence(source.isPersistence());
        result.setPluginNameList(new ArrayList<>(source.getPluginNameList()));

        // 显式复制全部压缩参数，避免迁移后因 null 或默认值差异改变上下文保留策略。
        AiAgentAutoConfigProperties.ContextCompressDefinition compression = source.getContextCompress();
        Module.ContextCompress targetCompression = new Module.ContextCompress();
        targetCompression.setType(compression.getType());
        targetCompression.setCompactionInterval(compression.getCompactionInterval());
        targetCompression.setOverlapSize(compression.getOverlapSize());
        targetCompression.setTokenThreshold(compression.getTokenThreshold());
        targetCompression.setEventRetentionSize(compression.getEventRetentionSize());
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
    private void validateApplicationIds(AiAgentAutoConfigProperties catalog, String catalogPath) {
        Map<String, String> paths = new LinkedHashMap<>();
        for (int i = 0; i < catalog.getApplications().size(); i++) {
            String id = catalog.getApplications().get(i).getId();
            String path = catalogPath + ".applications[" + i + "]";
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
     * 按 catalog 中的资源顺序加载一类模块，并为每个对象附加来源路径。
     */
    private <T> List<T> loadModules(List<String> locations, String prefix, Class<T> type) {
        List<T> values = new ArrayList<>();
        for (String location : locations) {
            T value = bind(location, prefix, type);
            setSourcePath(value, location);
            values.add(value);
        }
        return values;
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
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw error(location, "configuration resource does not exist");
        }
        try {
            MutablePropertySources sources = new MutablePropertySources();
            List<PropertySource<?>> loaded = yamlLoader.load(location, resource);
            loaded.forEach(sources::addLast);
            // 每个文件独立 Binder，确保“每份 YAML 只描述一个对象”不会污染全局 Spring Environment。
            T value = new Binder(from(sources))
                    .bind(prefix, Bindable.of(type))
                    .orElseThrow(() -> error(location, "missing configuration prefix '" + prefix + "'"));
            validate(value, location);
            return value;
        } catch (AgentConfigurationException exception) {
            throw exception;
        } catch (BindException exception) {
            // BindException 只保留属性名，不能透传其原始消息中的 rejected value。
            throw new AgentConfigurationException(
                    location + "." + exception.getName() + ": configuration binding failed");
        } catch (IOException exception) {
            throw new AgentConfigurationException(location + ": unable to read configuration resource");
        } catch (RuntimeException exception) {
            throw new AgentConfigurationException(location + ": configuration binding failed for '" + prefix + "'");
        }
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
