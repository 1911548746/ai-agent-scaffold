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

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, AgentAssemblyPipelineContext context) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AgentDefinition agentDefinition = requestParameter.getAgentDefinition();
        String appName = agentDefinition.getAppName();

        // descriptor 仅携带对外展示元数据，不让运行时依赖配置聚合对象。
        String agentId = agentDefinition.getAgentDescriptor().getAgentId();
        String agentName = agentDefinition.getAgentDescriptor().getAgentName();
        String agentDesc = agentDefinition.getAgentDescriptor().getAgentDesc();

        // App 不再注册自动压缩。压缩由 Runtime 协调器显式执行，避免后置摘要失败反向终止主回复。
        App app = createApp(agentDefinition, context);

        // 创建Runner
        Runner runner = createRunner(agentDefinition, app);

        // 策略树到此才产生完整运行时；在 AgentCompiler 发布前它仍是私有候选对象。
        RunConfig runConfig = createRunConfig(agentDefinition.getRunner());
        RuntimeCompactionPlan compactionPlan = runtimeCompactionPlanFactory.create(
                context.getLlmModel(), agentDefinition.getRunner().getContextCompress());
        AdkAgentRuntime runtime = new AdkAgentRuntime(
                appName, agentId, agentName, agentDesc, runner, runConfig, compactionPlan);

        context.setAgentRuntime(runtime);

        return router(requestParameter, context);
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, AgentAssemblyPipelineContext, AdkAgentRuntime> get(AgentAssemblyCommand command, AgentAssemblyPipelineContext context) throws Exception {
        return endNode;
    }

    // 创建App
    private App createApp(AgentDefinition definition, AgentAssemblyPipelineContext context) {

        String appName = definition.getAppName();
        RunnerDefinition runnerConfig = definition.getRunner();

        // runnerAgentName 运行时用的智能体名称
        String runnerAgentName = runnerConfig.getAgentName();
        BaseAgent baseAgent = context.getAgentGroup().get(runnerAgentName);

        // 配置插件列表
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

    // 创建Runner
    private Runner createRunner(AgentDefinition definition, App app) {

        RunnerDefinition runnerConfig = definition.getRunner();

        // 默认存储在内存
        BaseSessionService sessionService = new InMemorySessionService();
        // 配置event持久化
        if (runnerConfig != null && runnerConfig.getSessionStorage()) {
            sessionService = sessionStore;
        }
        // 每个 Runtime 独占一个服务实例；HTTP 上传端口随后通过已发布 Runtime 路由回该实例。
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
