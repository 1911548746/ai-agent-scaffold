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
import com.hh.domain.agent.adapter.repository.IMySqlSessionService;
import com.hh.domain.agent.model.entity.ArmoryCommandEntity;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.service.armory.AbstractArmorySupport;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class RunnerNode  extends AbstractArmorySupport {

    @Resource
    private IMySqlSessionService mySqlSessionService;

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

        // 注册到Spring容器
        registerBean(agentId, AiAgentRegisterVO.class, aiAgentRegisterVO);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    // 创建App
    private App createApp(AiAgentConfigTableVO aiAgentConfigTableVO, DefaultArmoryFactory.DynamicContext dynamicContext) {

        String appName = aiAgentConfigTableVO.getAppName();

        // runnerAgentName 运行时用的智能体名称
        String runnerAgentName = aiAgentConfigTableVO.getModule().getRunner().getAgentName();
        BaseAgent baseAgent = dynamicContext.getAgentGroup().get(runnerAgentName);

        // TODO 配置上下文压缩

        // 配置插件列表
        List<String> pluginNameList = aiAgentConfigTableVO.getModule().getRunner().getPluginNameList();
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
                .build();
    }

    // 创建Runner
    private Runner createRunner(AiAgentConfigTableVO aiAgentConfigTableVO, App app) {

        // 默认存储在内存
        BaseSessionService sessionService = new InMemorySessionService();
        // 配置event持久化
        if (aiAgentConfigTableVO.getModule().getRunner().getPersistence()) {
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
