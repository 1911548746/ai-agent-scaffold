package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageService;
import com.hh.infrastructure.agent.agent.artifact.ArtifactStorageServiceFactory;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlanFactory;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPluginFactory;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 装配单个 Agent Runtime 的末端节点。
 *
 * <p>前置节点已经完成配置解析、引用解析、模型和 Agent 图装配。本节点只把这些结果组合成
 * ADK {@link App}、{@link Runner} 和项目自己的 {@link AdkAgentRuntime}，不再承载配置校验或
 * 压缩策略的具体实现。</p>
 */
@Slf4j
@Component
public class RunnerNode extends AgentAssemblyNode {

    @Resource
    private EndNode endNode;

    @Resource
    private AdkSessionStore sessionStore;

    @Resource
    private RuntimeCompactionPlanFactory runtimeCompactionPlanFactory;

    @Resource
    private AgentObservabilityPluginFactory agentObservabilityPluginFactory;

    @Resource
    private ArtifactStorageServiceFactory artifactStorageServiceFactory;

    /**
     * 创建当前 Application 的运行时候选对象，并把它交给装配链的结束节点。
     *
     * <p>这里生成的 Runtime 尚未进入全局 Registry，只有整个编译链成功后才会被原子发布。</p>
     */
    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AgentDefinition agentDefinition = requestParameter.getAgentDefinition();
        String appName = agentDefinition.getAppName();

        // 只提取对外展示字段，避免已编译 Runtime 持有 Loader 的可变配置聚合对象。
        String agentId = agentDefinition.getAgentDescriptor().getAgentId();
        String agentName = agentDefinition.getAgentDescriptor().getAgentName();
        String agentDesc = agentDefinition.getAgentDescriptor().getAgentDesc();

        // App 只负责 ADK Agent、Plugin 等基础装配；Event 压缩由 Runtime 协调器显式调度，
        // 这样后置摘要失败不会反向终止已经成功的主回复。
        App app = createApp(agentDefinition, context);

        // Runner 持有 Session、Artifact、Memory 等本次 Runtime 独占的 ADK 服务实例。
        Runner runner = createRunner(agentDefinition, app);

        // 压缩计划在运行时装配阶段固定下来；发布前它仍只是私有候选对象。
        RunConfig runConfig = createRunConfig(agentDefinition.getRunner());
        RuntimeCompactionPlan compactionPlan = runtimeCompactionPlanFactory.create(
                context.getLlmModel(), agentDefinition.getRunner().getContextCompress());
        AdkAgentRuntime runtime = new AdkAgentRuntime(
                appName, agentId, agentName, agentDesc, runner, runConfig, compactionPlan);

        context.setAgentRuntime(runtime);

        return router(requestParameter, context);
    }

    /** 装配链已经完成，本节点始终把结果路由到结束节点。 */
    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return endNode;
    }

    /** 创建 ADK App，并装配根 Agent 与插件。 */
    private App createApp(AgentDefinition definition, AgentAssemblyPipelineContext context) {

        String appName = definition.getAppName();
        RunnerDefinition runnerConfig = definition.getRunner();

        // runnerAgentName 是前置 Agent 图校验后确定的根 Agent 名称。
        String runnerAgentName = runnerConfig.getAgentName();
        BaseAgent baseAgent = context.getAgentGroup().get(runnerAgentName);

        // 先放入系统观测插件，再按 Application 配置追加业务插件。
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        List<Plugin> plugins = new ArrayList<>();
        Set<String> pluginNames = new HashSet<>();

        // 系统观测能力属于运行时基础设施，不允许因业务 YAML 漏配而失效。
        Plugin observabilityPlugin = agentObservabilityPluginFactory.create(
                definition.getAgentDescriptor().getAgentId());
        plugins.add(observabilityPlugin);
        pluginNames.add(observabilityPlugin.getName());

        for (String pluginName : pluginNameList) {
            Plugin plugin = getBean(pluginName);
            if (plugin != null) {
                if (!pluginNames.add(plugin.getName())) {
                    throw new IllegalArgumentException(
                            "Agent '" + definition.getId() + "' contains duplicate Plugin name '"
                                    + plugin.getName() + "'");
                }
                plugins.add(plugin);
            }
        }

        return App.builder()
                .name(appName)
                .rootAgent(baseAgent)
                .plugins(plugins)
                .build();
    }

    /** 创建当前 Runtime 使用的 Session、Artifact 和 Memory 服务，并绑定到 ADK Runner。 */
    private Runner createRunner(AgentDefinition definition, App app) {

        RunnerDefinition runnerConfig = definition.getRunner();

        // 未开启 session-storage 时使用 ADK 内存 Session；这种模式不跨进程保留会话。
        BaseSessionService sessionService = new InMemorySessionService();
        // 开启 session-storage 后切换到共享的 MySQL Session Store。
        if (runnerConfig != null && runnerConfig.getSessionStorage()) {
            sessionService = sessionStore;
        }
        // 每个 Runtime 独占 ArtifactService；HTTP 上传端口随后通过已发布 Runtime 路由回同一实例，
        // 确保上传、LoadArtifactsTool 和 Session 删除不会使用不同的存储后端。
        ArtifactStorageService artifactService = artifactStorageServiceFactory.create(
                definition.getAppName(), runnerConfig.getArtifactStorage());

        return Runner.builder()
                .app(app)
                .artifactService(artifactService)
                .sessionService(sessionService)
                .memoryService(new InMemoryMemoryService())
                .build();
    }

    /**
     * 将 Agent 级执行策略编译成不可变 RunConfig，供同步、流式和多模态入口共同复用。
     */
    private RunConfig createRunConfig(RunnerDefinition runnerConfig) {
        boolean groupFunctionResponses = runnerConfig != null
                && Boolean.TRUE.equals(runnerConfig.getGroupFunctionResponsesInHistory());
        RunConfig.Builder builder = RunConfig.builder();
        if (groupFunctionResponses) {
            // false/未配置必须保持 Optional.empty，让 ADK 为 Gemini 3 等模型自行决定是否重排。
            enableGroupedFunctionHistoryCompatibility(builder);
        }
        return builder.build();
    }

    /**
     * 仅为确实需要 FC1/FC2/FR1/FR2 历史格式的非核心模型端点开启 ADK 兼容覆盖。
     */
    @SuppressWarnings("deprecation")
    private void enableGroupedFunctionHistoryCompatibility(RunConfig.Builder builder) {
        // ADK 1.7 已将该覆盖标记为特定端点兼容 API，集中隔离便于上游移除后统一迁移。
        builder.groupFunctionResponsesInHistoryOverride(Optional.of(true));
    }

}
