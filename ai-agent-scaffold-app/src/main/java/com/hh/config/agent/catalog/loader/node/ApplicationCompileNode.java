package com.hh.config.agent.catalog.loader.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.hh.config.agent.catalog.binding.AgentModuleConfig;
import com.hh.config.agent.catalog.binding.SkillModuleConfig;
import com.hh.config.agent.catalog.binding.ToolModuleConfig;
import com.hh.config.agent.catalog.binding.WorkflowModuleConfig;
import com.hh.config.agent.catalog.loader.support.AgentDefinitionConfigSupport;
import com.hh.config.agent.catalog.loader.pipeline.AgentDefinitionLoadNode;
import com.hh.config.agent.catalog.loader.command.AgentDefinitionLoadCommand;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadContext;
import com.hh.config.agent.catalog.loader.context.AgentDefinitionLoadPipelineContext;
import com.hh.config.agent.catalog.properties.AgentCatalogProperties;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ArtifactStorageDefinition;
import com.hh.domain.agent.model.enums.ArtifactStorageType;
import com.hh.domain.agent.model.definition.AgentWorkflowDefinition;
import com.hh.domain.agent.model.definition.ChatModelDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.LlmAgentDefinition;
import com.hh.domain.agent.model.definition.LocalToolDefinition;
import com.hh.domain.agent.model.definition.McpToolDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application 到领域 Definition 的编译节点。
 *
 * <p>前置条件是所有资源、引用和 workflow 图都已经通过验证。本节点以 application 为聚合根，
 * 收集可达的 Agent/Workflow，限制当前运行时的单模型约束，并把绑定 DTO 转成 domain Definition。</p>
 *
 * <p>本节点只产生内存中的候选 Definition，不创建 Runner、模型客户端或 MCP 连接；完整列表返回后
 * 才允许启动层交给 infrastructure 编译和原子发布。</p>
 */
@Component
public class ApplicationCompileNode extends AgentDefinitionLoadNode {

    private final AgentDefinitionConfigSupport support;
    private final EndNode endNode;

    public ApplicationCompileNode(AgentDefinitionConfigSupport support, EndNode endNode) {
        this.support = support;
        this.endNode = endNode;
    }

    @Override
    protected List<AgentDefinition> doApply(
            AgentDefinitionLoadCommand command,
            AgentDefinitionLoadPipelineContext pipelineContext) throws Exception {
        // 编译阶段只读取前置节点准备好的上下文快照，不再访问配置资源或执行图校验。
        AgentDefinitionLoadContext context = pipelineContext.getLoadContext();

        // 每个 application 都是一个独立编译单元；任一入口失败都会阻止整批候选 Definition 发布。
        List<AgentDefinition> definitions = context.getApplications().stream()
                .map(application -> compileApplication(application, context))
                .toList();

        // 用不可变列表保存候选结果，防止末节点或启动层意外修改本次编译快照。
        context.setDefinitions(List.copyOf(definitions));

        // 所有 application 编译成功后才进入返回节点。
        return router(command, pipelineContext);
    }

    private AgentDefinition compileApplication(
            AgentDefinitionLoadContext.LocatedApplication located,
            AgentDefinitionLoadContext context) {
        AgentCatalogProperties.ApplicationDefinition application = located.definition();
        String path = located.sourcePath();

        // 根入口必须二选一；空值和同时配置两种根都属于编译协议错误。
        boolean hasAgentRoot = support.hasText(application.getAgentRef());
        boolean hasWorkflowRoot = support.hasText(application.getWorkflowRef());
        if (hasAgentRoot == hasWorkflowRoot) {
            throw support.error(path + ".root", "exactly one of agent-ref or workflow-ref is required");
        }

        // 在开始收集可达图前确认根 ID 存在，避免后续 map.get 产生难以定位的空值错误。
        if (hasAgentRoot && !context.getAgents().containsKey(application.getAgentRef())) {
            throw support.error(path + ".agent-ref", "unknown root agent id '" + application.getAgentRef() + "'");
        }
        if (hasWorkflowRoot && !context.getWorkflows().containsKey(application.getWorkflowRef())) {
            throw support.error(path + ".workflow-ref", "unknown root workflow id '" + application.getWorkflowRef() + "'");
        }

        // LinkedHashSet 同时承担去重和稳定顺序职责，确保共享子节点只编译一次。
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        LinkedHashSet<String> workflowIds = new LinkedHashSet<>();

        // Agent 根直接加入 Agent 集合；Workflow 根则递归收集它能到达的两类节点。
        if (hasAgentRoot) agentIds.add(application.getAgentRef());
        else collectWorkflow(application.getWorkflowRef(), context.getWorkflows(), agentIds, workflowIds);

        // Workflow 不能只包含其他空 Workflow，最终必须解析出至少一个可装配的 Agent。
        if (agentIds.isEmpty()) throw support.error(path + ".root", "root does not resolve to an LLM agent");

        // 当前 ADK 装配边界要求一个 application 内的所有 Agent 共享同一个模型引用。
        Set<String> modelRefs = agentIds.stream()
                .map(context.getAgents()::get)
                .map(AgentModuleConfig::getModelRef)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (modelRefs.size() != 1) {
            throw support.error(path + ".root", "current ADK assembly requires all referenced agents to use one model-ref");
        }

        // 按稳定 Agent ID 顺序把配置 DTO 转换为领域 Agent Definition。
        List<LlmAgentDefinition> agents = agentIds.stream()
                .map(context.getAgents()::get)
                .map(agent -> compileAgent(agent, context.getTools(), context.getSkills()))
                .toList();

        // 按图节点生成的后序顺序筛选当前 application 可达的 Workflow，保证子工作流先出现。
        List<AgentWorkflowDefinition> workflows = context.getWorkflowOrder().stream()
                .filter(workflowIds::contains)
                .map(context.getWorkflows()::get)
                .map(workflow -> compileWorkflow(workflow, context.getAgents(), context.getWorkflows()))
                .toList();

        // 根名称是领域 Runner 的入口标识；Agent 根和 Workflow 根都使用各自配置名称。
        String rootName = hasAgentRoot
                ? context.getAgents().get(application.getAgentRef()).getName()
                : context.getWorkflows().get(application.getWorkflowRef()).getName();

        // 组装 application 级 Definition；此处仅构造数据，不创建运行时客户端或外部连接。
        return AgentDefinition.builder()
                .id(application.getId()).enabled(application.isEnabled()).sourcePath(path)
                .appName(application.getAppName()).agentDescriptor(toAgentDescriptor(application))
                .rootAgentName(rootName).chatModel(toChatModel(context.getModels().get(modelRefs.iterator().next())))
                .agents(List.copyOf(agents)).agentWorkflows(List.copyOf(workflows))
                .runner(toRunner(application.getRunner(), rootName)).build();
    }

    private void collectWorkflow(String id, Map<String, WorkflowModuleConfig> workflows,
                                 LinkedHashSet<String> agentIds, LinkedHashSet<String> workflowIds) {
        // 已收集的 Workflow 不再递归，避免共享子图导致重复遍历。
        if (!workflowIds.add(id)) return;

        // 当前 Workflow 的直接 Agent 引用加入集合，保留首次发现顺序。
        WorkflowModuleConfig workflow = workflows.get(id);
        agentIds.addAll(workflow.getSubAgentRefs());

        // 继续深入子 Workflow，最终得到根节点可达的完整闭包。
        workflow.getSubWorkflowRefs().forEach(child -> collectWorkflow(child, workflows, agentIds, workflowIds));
    }

    private LlmAgentDefinition compileAgent(AgentModuleConfig agent,
                                            Map<String, ToolModuleConfig> tools,
                                            Map<String, SkillModuleConfig> skills) {
        // 先复制 Agent 自身的领域字段，工具和 Skill 在下面分别转成领域对象。
        LlmAgentDefinition result = new LlmAgentDefinition();
        result.setName(agent.getName());
        result.setDescription(agent.getDescription());
        result.setInstruction(agent.getInstruction());
        result.setOutputKey(agent.getOutputKey());
        result.setMaxSteps(agent.getMaxSteps());

        // 分开保存 MCP 和本地工具，避免 infrastructure 装配时再次判断配置 DTO 类型。
        List<McpToolDefinition> mcpTools = new ArrayList<>();
        List<LocalToolDefinition> localTools = new ArrayList<>();
        String skillsDirectory = null;
        for (String ref : agent.getToolRefs()) {
            // 引用已在校验节点确认有效；这里按工具类型选择对应的领域转换器。
            ToolModuleConfig tool = tools.get(ref);
            if ("local".equalsIgnoreCase(tool.getType())) {
                localTools.add(toLocalTool(tool));
            } else {
                mcpTools.add(toMcpTool(tool));
            }
        }
        for (int i = 0; i < agent.getSkillRefs().size(); i++) {
            // 当前装配协议只允许每个 Agent 一个 Skill 工具集，因此需要显式阻止第二个目录。
            SkillModuleConfig skill = skills.get(agent.getSkillRefs().get(i));
            if (skillsDirectory != null) {
                throw support.error(agent.getSourcePath() + ".skill-refs[" + i + "]",
                        "current ADK assembly supports one skill toolset per agent");
            }
            // Skill 目录也支持环境变量占位符，解析后写入领域 Definition。
            skillsDirectory = support.resolveRequired(skill.getDirectory(), skill.getSourcePath() + ".directory");
        }

        // 工具和 Skill 转换完成后一次性写入 Agent，保持领域对象不依赖配置绑定 DTO。
        result.setMcpToolList(mcpTools);
        result.setLocalToolList(localTools);
        result.setSkillsDir(skillsDirectory);
        return result;
    }

    private McpToolDefinition toMcpTool(ToolModuleConfig tool) {
        // MCP transport 已在校验节点保证恰好一个；这里仅复制对应 transport 的领域参数。
        McpToolDefinition result = new McpToolDefinition();
        ToolModuleConfig.McpDefinition source = tool.getMcp();
        if (source.getSse() != null) {
            // SSE 只解析连接 URL；其他可选连接参数按配置原值传入领域层。
            McpToolDefinition.SSEServerParameters target = new McpToolDefinition.SSEServerParameters();
            target.setName(source.getSse().getName());
            target.setUrl(support.resolveRequired(
                    source.getSse().getUrl(), tool.getSourcePath() + ".mcp.sse.url"));
            target.setSseEndpoint(source.getSse().getSseEndpoint());
            target.setTimeout(source.getSse().getTimeout());
            target.setRequestTimeout(source.getSse().getRequestTimeout());
            result.setSse(target);
        }
        if (source.getHttp() != null) {
            // HTTP Header 逐项解析占位符，但异常路径只包含字段位置，不暴露 header 值。
            McpToolDefinition.HttpServerParameters target = new McpToolDefinition.HttpServerParameters();
            target.setName(source.getHttp().getName());
            target.setUrl(support.resolveRequired(
                    source.getHttp().getUrl(), tool.getSourcePath() + ".mcp.http.url"));
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            source.getHttp().getHeaders().forEach((name, value) -> headers.put(
                    name, support.resolveRequired(value, tool.getSourcePath() + ".mcp.http.headers")));
            target.setHeaders(headers);
            target.setTimeout(source.getHttp().getTimeout());
            target.setRequestTimeout(source.getHttp().getRequestTimeout());
            result.setHttp(target);
        }
        if (source.getStdio() != null) {
            // stdio 命令、环境变量属于敏感连接配置，逐项解析后再复制到领域对象。
            McpToolDefinition.StdioServerParameters target = new McpToolDefinition.StdioServerParameters();
            target.setName(source.getStdio().getName());
            target.setCommand(support.resolveRequired(
                    source.getStdio().getCommand(), tool.getSourcePath() + ".mcp.stdio.command"));
            target.setArgs(new ArrayList<>(source.getStdio().getArgs()));
            Map<String, String> env = new java.util.LinkedHashMap<>();
            source.getStdio().getEnv().forEach((name, value) -> env.put(
                    name, support.resolveRequired(value, tool.getSourcePath() + ".mcp.stdio.env." + name)));
            target.setEnv(env);
            result.setStdio(target);
        }
        // 返回只含领域类型的数据对象，外部客户端的创建由后续 infrastructure 阶段负责。
        return result;
    }

    private LocalToolDefinition toLocalTool(ToolModuleConfig tool) {
        // 本地工具只保留 Bean 和方法定位信息，实际反射/调用仍由运行时装配层处理。
        LocalToolDefinition result = new LocalToolDefinition();
        result.setBeanName(tool.getBeanName());
        result.setMethodName(tool.getMethodName());
        return result;
    }

    private AgentWorkflowDefinition compileWorkflow(WorkflowModuleConfig workflow,
                                                     Map<String, AgentModuleConfig> agents,
                                                     Map<String, WorkflowModuleConfig> workflows) {
        // Workflow 领域对象使用名称引用，而不是把配置层的稳定 ID 直接泄漏给装配实现。
        AgentWorkflowDefinition result = new AgentWorkflowDefinition();
        result.setType(workflow.getType());
        result.setName(workflow.getName());
        result.setDescription(workflow.getDescription());
        result.setInstruction(workflow.getInstruction());
        result.setMaxIterations(workflow.getMaxIterations());
        List<String> children = new ArrayList<>();
        // 先按配置顺序加入直接 Agent，再加入直接子 Workflow。
        workflow.getSubAgentRefs().stream().map(agents::get).map(AgentModuleConfig::getName).forEach(children::add);
        workflow.getSubWorkflowRefs().stream().map(workflows::get).map(WorkflowModuleConfig::getName).forEach(children::add);
        result.setSubAgents(children);
        return result;
    }

    private AgentDescriptor toAgentDescriptor(AgentCatalogProperties.ApplicationDefinition source) {
        // application 的运行时展示元数据独立转换，避免领域 Definition 持有配置 DTO。
        AgentDescriptor result = new AgentDescriptor();
        result.setAgentId(source.getAgentId());
        result.setAgentName(source.getAgentName());
        result.setAgentDesc(source.getAgentDesc());
        return result;
    }

    private ChatModelDefinition toChatModel(com.hh.config.agent.catalog.binding.ModelModuleConfig source) {
        // 将已解析的模型连接参数复制为领域模型定义，后续工厂据此创建客户端。
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

    private RunnerDefinition toRunner(AgentCatalogProperties.RunnerDefinition source, String rootName) {
        // Runner 定义只描述运行参数；本节点不拥有 Runner、Plugin 或上下文压缩资源的生命周期。
        RunnerDefinition result = new RunnerDefinition();
        result.setAgentName(rootName);
        result.setSessionStorage(source.isSessionStorage());
        result.setGroupFunctionResponsesInHistory(source.isGroupFunctionResponsesInHistory());
        AgentCatalogProperties.ArtifactStorageDefinition configuredStorage = source.getArtifactStorage();
        ArtifactStorageDefinition artifactStorage = new ArtifactStorageDefinition();
        artifactStorage.setType(ArtifactStorageType.valueOf(
                configuredStorage.getType().trim().toUpperCase(java.util.Locale.ROOT)));
        artifactStorage.setTtl(configuredStorage.getTtl());
        artifactStorage.setLifecycleEnabled(configuredStorage.isLifecycleEnabled());
        result.setArtifactStorage(artifactStorage);
        result.setPluginNameList(new ArrayList<>(source.getPluginNameList()));
        // 单独复制上下文压缩配置，避免领域层共享 Spring 绑定的可变嵌套对象。
        AgentCatalogProperties.ContextCompressDefinition compression = source.getContextCompress();
        ContextCompressionDefinition target = new ContextCompressionDefinition();
        target.setType(compression.getType());
        target.setCompactionInterval(compression.getCompactionInterval());
        target.setOverlapSize(compression.getOverlapSize());
        target.setTokenThreshold(compression.getTokenThreshold());
        target.setEventRetentionSize(compression.getEventRetentionSize());
        target.setInvocationRetentionSize(compression.getInvocationRetentionSize());
        result.setContextCompress(target);
        return result;
    }

    @Override
    public StrategyHandler<AgentDefinitionLoadCommand, AgentDefinitionLoadPipelineContext, List<AgentDefinition>> get(AgentDefinitionLoadCommand command, AgentDefinitionLoadPipelineContext context) {
        // Definition 列表已经完整生成，下一步只负责返回，不再继续执行编译逻辑。
        return endNode;
    }
}
