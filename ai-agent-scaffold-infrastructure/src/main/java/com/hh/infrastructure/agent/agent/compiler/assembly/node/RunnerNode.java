package com.hh.infrastructure.agent.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.google.adk.summarizer.SlidingWindowEventCompactor;
import com.google.adk.summarizer.TailRetentionEventCompactor;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.AgentAssemblyPipelineContext;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.compaction.RuntimeCompactionPlan;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPluginFactory;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblyNode;
import com.hh.types.enums.ContextCompressTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private CompactionCleanupPolicyRegistry compactionCleanupPolicyRegistry;

    @Resource
    private AgentObservabilityPluginFactory agentObservabilityPluginFactory;

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
        RuntimeCompactionPlan compactionPlan = createCompactionPlan(
                context, agentDefinition.getRunner().getContextCompress());
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
        if (runnerConfig != null && runnerConfig.getPersistence()) {
            sessionService = sessionStore;
        }
        // 会话运行节点
        return Runner.builder()
                .app(app)
                .artifactService(new InMemoryArtifactService())
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

    /**
     * 根据 Runner 的压缩配置选择清理策略。
     *
     * <p>只有显式 slidingWindow 才构建滑动窗口；其余值在 {@link #createApp} 中均会构建 fixedSize，
     * 因此此处也必须使用 fixedSize 作为默认值，避免 ADK 压缩方式与 Event 清理语义不一致。</p>
     */
    private TrackingLlmEventSummarizer createTrackingSummarizer(AgentAssemblyPipelineContext context, ContextCompressionDefinition contextCompressConfig) {

        String type = contextCompressConfig == null ? ContextCompressTypeEnum.FIXED_SIZE.getType()
                : contextCompressConfig.getType();

        CompactionCleanupContext cleanupContext = new CompactionCleanupContext(
                contextCompressConfig == null ? null : contextCompressConfig.getOverlapSize(),
                contextCompressConfig == null ? null : contextCompressConfig.getEventRetentionSize());

        return new TrackingLlmEventSummarizer(
                new LlmEventSummarizer(context.getLlmModel()),
                compactionCleanupPolicyRegistry.resolve(type),
                cleanupContext);
    }

    /**
     * 将 YAML 压缩配置编译为 Runtime 维护计划，而不是交给 ADK 自动串入主调用 Flowable。
     */
    private RuntimeCompactionPlan createCompactionPlan(
            AgentAssemblyPipelineContext context,
            ContextCompressionDefinition configured) {

        ContextCompressionDefinition config = configured == null ? new ContextCompressionDefinition() : configured;
        ContextCompressTypeEnum type = ContextCompressTypeEnum.getByType(config.getType());

        if (type == ContextCompressTypeEnum.SLIDING_WINDOW) {
            EventsCompactionConfig adkConfig = EventsCompactionConfig.builder()
                    .compactionInterval(config.getCompactionInterval())
                    .overlapSize(config.getOverlapSize())
                    .summarizer(createTrackingSummarizer(context, config))
                    .build();
            return new RuntimeCompactionPlan(type, new SlidingWindowEventCompactor(adkConfig), 0, true);
        }
        if (type == ContextCompressTypeEnum.PURE_SLIDING_WINDOW) {
            EventsCompactionConfig adkConfig = EventsCompactionConfig.builder()
                    .compactionInterval(1)
                    .overlapSize(config.getInvocationRetentionSize())
                    .summarizer(createPureSlidingWindowSummarizer(config))
                    .build();
            return new RuntimeCompactionPlan(type, new SlidingWindowEventCompactor(adkConfig), 0, true);
        }

        // fixedSize 在当前用户消息写入前运行；阈值由协调器使用“历史 + 待发送消息”预检，
        // 因此此处传 0 强制 TailRetention 只要被选中就压缩既有历史。
        return new RuntimeCompactionPlan(
                ContextCompressTypeEnum.FIXED_SIZE,
                new TailRetentionEventCompactor(
                        createTrackingSummarizer(context, config),
                        config.getEventRetentionSize(),
                        0),
                config.getTokenThreshold(),
                true);
    }

    /**
     * pureSlidingWindow 复用 TrackingLlmEventSummarizer 的精确删除 metadata，但不创建 LLM 摘要器。
     */
    private TrackingLlmEventSummarizer createPureSlidingWindowSummarizer(
            ContextCompressionDefinition contextCompressConfig) {
        int retentionSize = contextCompressConfig.getInvocationRetentionSize();
        return new TrackingLlmEventSummarizer(
                new PureSlidingWindowEventSummarizer(retentionSize),
                compactionCleanupPolicyRegistry.resolve(ContextCompressTypeEnum.PURE_SLIDING_WINDOW.getType()),
                new CompactionCleanupContext(retentionSize, null));
    }

}
