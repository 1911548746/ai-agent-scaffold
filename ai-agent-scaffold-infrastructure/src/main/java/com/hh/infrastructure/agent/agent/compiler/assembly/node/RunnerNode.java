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
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.infrastructure.agent.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.infrastructure.agent.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.infrastructure.agent.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.agent.runtime.observability.plugin.AgentObservabilityPluginFactory;
import com.hh.infrastructure.agent.agent.compiler.assembly.pipeline.AgentAssemblySupport;
import com.hh.types.enums.ContextCompressTypeEnum;
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
public class RunnerNode extends AgentAssemblySupport {

    @Resource
    private AdkSessionStore sessionStore;

    @Resource
    private CompactionCleanupPolicyRegistry compactionCleanupPolicyRegistry;

    @Resource
    private AgentObservabilityPluginFactory agentObservabilityPluginFactory;

    @Override
    protected AdkAgentRuntime doApply(AgentAssemblyCommand requestParameter, DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AgentDefinition agentDefinition = requestParameter.getAgentDefinition();
        String appName = agentDefinition.getAppName();

        // descriptor 仅携带对外展示元数据，不让运行时依赖配置聚合对象。
        String agentId = agentDefinition.getAgentDescriptor().getAgentId();
        String agentName = agentDefinition.getAgentDescriptor().getAgentName();
        String agentDesc = agentDefinition.getAgentDescriptor().getAgentDesc();

        // 创建App
        App app = createApp(agentDefinition, dynamicContext);

        // 创建Runner
        Runner runner = createRunner(agentDefinition, app);

        // 策略树到此才产生完整运行时；在 AgentCompiler 发布前它仍是私有候选对象。
        RunConfig runConfig = createRunConfig(agentDefinition.getRunner());
        AdkAgentRuntime runtime = new AdkAgentRuntime(
                appName, agentId, agentName, agentDesc, runner, runConfig);

        // 返回临时构建结果；AgentCompiler 在所有 Agent 成功后统一发布 Registry 快照。
        return runtime;
    }

    @Override
    public StrategyHandler<AgentAssemblyCommand, DynamicContext, AdkAgentRuntime> get(AgentAssemblyCommand command, DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // 创建App
    private App createApp(AgentDefinition definition, DynamicContext dynamicContext) {

        String appName = definition.getAppName();
        RunnerDefinition runnerConfig = definition.getRunner();

        // runnerAgentName 运行时用的智能体名称
        String runnerAgentName = runnerConfig.getAgentName();
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(runnerAgentName);

        // 上下文压缩
        ContextCompressionDefinition contextCompressConfig = runnerConfig.getContextCompress();
        EventsCompactionConfig compactionConfig = null;
        ContextCompressTypeEnum compressType = contextCompressConfig == null
                ? ContextCompressTypeEnum.FIXED_SIZE
                : ContextCompressTypeEnum.getByType(contextCompressConfig.getType());

        // 根据上下文压缩类型进行不同的配置
        // 滑动窗口
        /*
          旧的一批 events -> summarizer 生成一条 compaction event
          最近 overlap 的一部分 events -> 原样保留
          compactionInterval：累计约多少轮（invocation）对话后触发压缩。
          overlapSize：压缩时覆盖上一次压缩前多少轮（invocation）对话的原始 events。
         */
        if (ContextCompressTypeEnum.SLIDING_WINDOW.equals(compressType)) {
            compactionConfig = EventsCompactionConfig.builder()
                    .compactionInterval(contextCompressConfig.getCompactionInterval())
                    .overlapSize(contextCompressConfig.getOverlapSize())
                    // SlidingWindow 策略会保留 overlap invocation 的原始 Event，避免下一轮摘要丢失窗口重叠。
                    .summarizer(createTrackingSummarizer(dynamicContext, contextCompressConfig))
                    .build();
        }

        // 固定token阈值
        /*
         * 当上下文 token 超过阈值时，
         * 保留最近 eventRetentionSize 条 events，
         * 更早的 events 交给 summarizer 压成 compaction event。
         */
        else if (ContextCompressTypeEnum.FIXED_SIZE.equals(compressType)) {
            compactionConfig = EventsCompactionConfig.builder()
                    .tokenThreshold(null != contextCompressConfig ? contextCompressConfig.getTokenThreshold() : new ContextCompressionDefinition().getTokenThreshold())
                    .eventRetentionSize(null != contextCompressConfig ? contextCompressConfig.getEventRetentionSize() : new ContextCompressionDefinition().getEventRetentionSize())
                    // 默认摘要能力仍由 ADK 提供；包装器按 fixedSize 语义记录真正被新摘要覆盖的 Event。
                    .summarizer(createTrackingSummarizer(dynamicContext, contextCompressConfig))
                    .build();
        }
        // 纯滑动窗口仍使用 ADK 的窗口选择与 compaction marker 回放机制，但 marker 不携带模型摘要内容。
        else if (ContextCompressTypeEnum.PURE_SLIDING_WINDOW.equals(compressType)) {
            compactionConfig = EventsCompactionConfig.builder()
                    // 每完成一轮都检查窗口；summarizer 仅在完整 invocation 超过保留容量时返回 marker。
                    .compactionInterval(1)
                    .overlapSize(contextCompressConfig.getInvocationRetentionSize())
                    .summarizer(createPureSlidingWindowSummarizer(contextCompressConfig))
                    .build();
        }

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

        // TODO 没有配置压缩策略时，不会进行上下文管理
        var appBuilder = App.builder()
                .name(appName)
                .rootAgent(baseAgent)
                .plugins(plugins);
        if (compactionConfig != null) {
            appBuilder.eventsCompactionConfig(compactionConfig);
        }
        return appBuilder.build();
    }

    // 创建Runner
    private Runner createRunner(AgentDefinition definition, App app) {

        RunnerDefinition runnerConfig = definition.getRunner();

        // 默认存储在内存
        BaseSessionService sessionService = new InMemorySessionService();
        // 配置event持久化
        if (runnerConfig != null && runnerConfig.getPersistence()) {
            // Runner 只依赖会话存储能力，不能感知底层是否为 MySQL。
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
    private TrackingLlmEventSummarizer createTrackingSummarizer(DynamicContext dynamicContext, ContextCompressionDefinition contextCompressConfig) {

        String type = contextCompressConfig == null ? ContextCompressTypeEnum.FIXED_SIZE.getType()
                : contextCompressConfig.getType();

        CompactionCleanupContext cleanupContext = new CompactionCleanupContext(
                contextCompressConfig == null ? null : contextCompressConfig.getOverlapSize(),
                contextCompressConfig == null ? null : contextCompressConfig.getEventRetentionSize());

        return new TrackingLlmEventSummarizer(
                new LlmEventSummarizer(dynamicContext.getLlmModel()),
                compactionCleanupPolicyRegistry.resolve(type),
                cleanupContext);
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
