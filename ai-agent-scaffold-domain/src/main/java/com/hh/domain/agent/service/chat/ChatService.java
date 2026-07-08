package com.hh.domain.agent.service.chat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.valobj.AgentInfo;
import com.hh.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.hh.domain.agent.service.IChatService;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    /**
     * 获取智能体列表
     */
    @Override
    public List<AgentInfo> queryAgentList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        List<AgentInfo> agentList = new ArrayList<>();

        if (tables != null) {
            for (AiAgentConfigTableVO aiAgentConfig : tables.values()) {
                agentList.add(aiAgentConfig.getAgentInfo());
            }
        }

        return agentList;
    }

    /**
     * 创建会话
     */
    @Override
    public String createSession(String agentId, String userId) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        // TODO 业务异常
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found");
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        return userSessions.computeIfAbsent(userId, uid -> {
            Session session = runner.sessionService()
                    .createSession(appName, uid)
                    .blockingGet();
            return session.id();
        });
    }


    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        // TODO 业务异常
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found");
        }

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        // TODO 业务异常
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found");
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMessage = Content.fromParts(Part.fromText(message));

        List<String> outputs = new ArrayList<>();
        runner.runAsync(userId, sessionId, userMessage)
                .blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        // TODO 业务异常
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found");
        }

        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        Runner runner = aiAgentRegisterVO.getRunner();

        Content userMessage = Content.fromParts(Part.fromText(message));

        return runner.runAsync(userId, sessionId, userMessage, runConfig);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        // TODO 业务异常
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found");
        }


        // 构建参数
        List<Part> parts = new ArrayList<>();

        // 文本内容
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (texts != null) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        // 文件内容
        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (files != null) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        // 字节数据
        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (inlineDatas != null) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content userMessage = Content.builder()
                .parts(parts)
                .build();

        // 获取运行体
        Runner runner = aiAgentRegisterVO.getRunner();

        List<String> outputs = new ArrayList<>();
        runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), userMessage)
                .blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }
}
