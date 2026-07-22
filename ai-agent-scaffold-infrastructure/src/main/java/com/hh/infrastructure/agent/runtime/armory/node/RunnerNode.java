package com.hh.infrastructure.agent.runtime.armory.node;

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
import com.hh.infrastructure.agent.runtime.session.AdkSessionStore;
import com.hh.infrastructure.agent.runtime.ArmoryCommand;
import com.hh.domain.agent.model.definition.AgentDefinition;
import com.hh.domain.agent.model.definition.ContextCompressionDefinition;
import com.hh.domain.agent.model.definition.RunnerDefinition;
import com.hh.infrastructure.agent.runtime.AdkAgentRuntime;
import com.hh.infrastructure.agent.runtime.armory.AbstractArmorySupport;
import com.hh.infrastructure.agent.runtime.armory.factory.DefaultArmoryFactory;
import com.hh.types.enums.ContextCompressTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class RunnerNode  extends AbstractArmorySupport {

    @Resource
    private AdkSessionStore sessionStore;

    @Override
    protected AdkAgentRuntime doApply(ArmoryCommand requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
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
    public StrategyHandler<ArmoryCommand, DefaultArmoryFactory.DynamicContext, AdkAgentRuntime> get(ArmoryCommand armoryCommand, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // 创建App
    private App createApp(AgentDefinition definition, DefaultArmoryFactory.DynamicContext dynamicContext) {

        String appName = definition.getAppName();
        RunnerDefinition runnerConfig = definition.getRunner();

        // runnerAgentName 运行时用的智能体名称
        String runnerAgentName = runnerConfig.getAgentName();
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(runnerAgentName);

        // 上下文压缩
        ContextCompressionDefinition contextCompressConfig = runnerConfig.getContextCompress();
        EventsCompactionConfig compactionConfig;

        // 根据上下文压缩类型进行不同的配置
        // 固定token阈值
        /*
         * 当上下文 token 超过阈值时，
         * 保留最近 eventRetentionSize 条 events，
         * 更早的 events 交给 summarizer 压成 compaction event。
         */
        if (null != contextCompressConfig && ContextCompressTypeEnum.FIXED_SIZE.equals(ContextCompressTypeEnum.getByType(contextCompressConfig.getType()))) {
            compactionConfig = EventsCompactionConfig.builder()
                    .tokenThreshold(contextCompressConfig.getTokenThreshold())
                    .eventRetentionSize(contextCompressConfig.getEventRetentionSize())
                    .summarizer(new LlmEventSummarizer(dynamicContext.getLlmModel()))
                    .build();
        }
        // 滑动窗口
        /*
          旧的一批 events -> summarizer 生成一条 compaction event
          最近 overlap 的一部分 events -> 原样保留
          compactionInterval：累计约多少轮（invocation）对话后触发压缩。
          overlapSize：压缩时保留最近多少轮（invocation）对话的原始 events。
          (注意，overlapSize 会包含一轮已经压缩的 compact event, 因此实际重叠的轮数会少一, 需要确保 overlapSize 大于等于 1。)
          一轮（invocation）对话包含多个event。
         */
        else {
            compactionConfig = EventsCompactionConfig.builder()
                    .compactionInterval(null != contextCompressConfig ? contextCompressConfig.getCompactionInterval() : 12)     // TODO 能不能直接用ContextCompress类里面的值？
                    .overlapSize(null != contextCompressConfig ? contextCompressConfig.getOverlapSize() : 2)
                    .summarizer(new LlmEventSummarizer(dynamicContext.getLlmModel()))
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

}
