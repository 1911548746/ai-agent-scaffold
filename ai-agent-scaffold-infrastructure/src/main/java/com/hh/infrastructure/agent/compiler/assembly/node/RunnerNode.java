package com.hh.infrastructure.agent.compiler.assembly.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.BaseAgent;
import com.google.adk.apps.App;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.plugins.Plugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.summarizer.EventsCompactionConfig;
import com.google.adk.summarizer.LlmEventSummarizer;
import com.hh.infrastructure.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.CompactionCleanupPolicyRegistry;
import com.hh.infrastructure.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import com.hh.infrastructure.agent.compiler.assembly.context.DynamicContext;
import com.hh.infrastructure.agent.session.AdkSessionStore;
import com.hh.infrastructure.agent.compiler.assembly.command.AgentAssemblyCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.compiler.assembly.pipeline.AgentAssemblySupport;
import com.hh.types.enums.ContextCompressTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class RunnerNode extends AgentAssemblySupport {

    @Resource
    private AdkSessionStore sessionStore;

    @Resource
    private CompactionCleanupPolicyRegistry compactionCleanupPolicyRegistry;

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
        AdkAgentRuntime runtime = new AdkAgentRuntime(appName, agentId, agentName, agentDesc, runner);

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
        EventsCompactionConfig compactionConfig;

        // 根据上下文压缩类型进行不同的配置
        // 滑动窗口
        /*
          旧的一批 events -> summarizer 生成一条 compaction event
          最近 overlap 的一部分 events -> 原样保留
          compactionInterval：累计约多少轮（invocation）对话后触发压缩。
          overlapSize：压缩时覆盖上一次压缩前多少轮（invocation）对话的原始 events。
         */
        if (null != contextCompressConfig && ContextCompressTypeEnum.SLIDING_WINDOW.equals(ContextCompressTypeEnum.getByType(contextCompressConfig.getType()))) {
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
        else {
            compactionConfig = EventsCompactionConfig.builder()
                    .tokenThreshold(null != contextCompressConfig ? contextCompressConfig.getTokenThreshold() : new ContextCompressionDefinition().getTokenThreshold())
                    .eventRetentionSize(null != contextCompressConfig ? contextCompressConfig.getEventRetentionSize() : new ContextCompressionDefinition().getEventRetentionSize())
                    // 默认摘要能力仍由 ADK 提供；包装器按 fixedSize 语义记录真正被新摘要覆盖的 Event。
                    .summarizer(createTrackingSummarizer(dynamicContext, contextCompressConfig))
                    .build();
        }

        // 配置插件列表
        List<String> pluginNameList = runnerConfig.getPluginNameList();
        List<Plugin> plugins = new ArrayList<>();

        for (String pluginName : pluginNameList) {
            Plugin plugin = getBean(pluginName);
            if (plugin != null) {
                plugins.add(plugin);
            }
        }

        return App.builder()
                .name(appName)
                .rootAgent(baseAgent)
                .plugins(plugins)
                .eventsCompactionConfig(compactionConfig)
                .build();
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

}
