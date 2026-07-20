package com.hh.domain.agent.service.armory.node;

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
import com.hh.domain.agent.adapter.repository.IMySqlSessionServiceRepository;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.model.legacyconfig.Module;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
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
    private IMySqlSessionServiceRepository mySqlSessionService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - RunnerNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        String appName = aiAgentConfigTableVO.getAppName();

        // agentInfo 智能体信息 - 对外展示的(应用名称)
        String agentId = aiAgentConfigTableVO.getAgentInfo().getAgentId();
        String agentName = aiAgentConfigTableVO.getAgentInfo().getAgentName();
        String agentDesc = aiAgentConfigTableVO.getAgentInfo().getAgentDesc();

        // 创建App
        App app = createApp(aiAgentConfigTableVO, dynamicContext);

        // 创建Runner
        Runner runner = createRunner(aiAgentConfigTableVO, app);

        AiAgentRegisterVO aiAgentRegisterVO = AiAgentRegisterVO.builder()
                .appName(appName)
                .agentId(agentId)
                .agentName(agentName)
                .agentDesc(agentDesc)
                .runner(runner)
                .build();

        // 返回临时构建结果；AgentCompiler 在所有 Agent 成功后统一发布 Registry 快照。
        return aiAgentRegisterVO;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // 创建App
    private App createApp(AiAgentConfigTableVO aiAgentConfigTableVO, DefaultArmoryFactory.DynamicContext dynamicContext) {

        String appName = aiAgentConfigTableVO.getAppName();
        Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        // runnerAgentName 运行时用的智能体名称
        String runnerAgentName = runnerConfig.getAgentName();
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(runnerAgentName);

        // 上下文压缩
        Module.ContextCompress contextCompressConfig = runnerConfig.getContextCompress();
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
    private Runner createRunner(AiAgentConfigTableVO aiAgentConfigTableVO, App app) {

        Module.Runner runnerConfig = aiAgentConfigTableVO.getModule().getRunner();

        // 默认存储在内存
        BaseSessionService sessionService = new InMemorySessionService();
        // 配置event持久化
        if (runnerConfig != null && runnerConfig.getPersistence()) {
            sessionService = mySqlSessionService;
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
