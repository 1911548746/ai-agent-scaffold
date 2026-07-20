package com.hh.domain.agent.service.chat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.domain.agent.adapter.repository.IConversationArchiveRepository;
import com.hh.domain.agent.adapter.repository.IMessageArchiveRepository;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.legacyconfig.AgentInfo;
import com.hh.domain.agent.model.legacyconfig.AiAgentConfigTableVO;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.hh.domain.agent.service.IChatService;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.validation.RequestPayloadLimits;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IConversationArchiveRepository conversationArchiveRepository;
    @Resource
    private IMessageArchiveRepository messageArchiveRepository;

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

        if (aiAgentRegisterVO == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        // 会话身份由 appName、userId、sessionId 共同确定，不能按 userId 缓存；
        // 否则同一用户切换 Agent 或创建第二个会话时会错误复用旧会话。
        Session session = runner.sessionService()
                .createSession(appName, userId)
                .blockingGet();
        String sessionId = session.id();

        // 创建对应的会话归档记录；后续对话只能使用调用方显式传入的 sessionId。
        conversationArchiveRepository.findOrCreateConversation(appName, userId, sessionId);

        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (aiAgentRegisterVO == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchiveRepository.findOrCreateConversation(appName, userId, sessionId);

        // 保存用户消息
        messageArchiveRepository.saveUserMessage(conversationId, appName, userId, sessionId, message);

        Content userMessage = Content.fromParts(Part.fromText(message));

        List<String> outputs = new ArrayList<>();

        runner.runAsync(userId, sessionId, userMessage)
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        outputs.add(event.stringifyContent());
                    }
                });

        String content = String.join("\n", outputs);
        // 保存AI回复
        messageArchiveRepository.saveAssistantMessage(conversationId, appName, userId, sessionId, content);

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (aiAgentRegisterVO == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }

        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchiveRepository.findOrCreateConversation(appName, userId, sessionId);
        // 保存用户消息
        messageArchiveRepository.saveUserMessage(conversationId, appName, userId, sessionId, message);

        Content userMessage = Content.fromParts(Part.fromText(message));

        // 在订阅前创建 pending 消息，确保取消和异常不会被误存为 completed 回复。
        Long assistantMessageId = messageArchiveRepository.createPendingAssistantMessage(
                conversationId, appName, userId, sessionId);

        // TODO 流式输出时 events 表中一个 token 存一条记录
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMessage, runConfig);

        // 只有拿到 ADK 明确标记的最终响应，正常完成时才允许写入完整 assistant 内容。
        StringBuilder assistantBuffer = new StringBuilder();
        AtomicBoolean finalResponseReceived = new AtomicBoolean(false);
        return events.doOnNext(event -> {
            if (event.finalResponse()) {
                assistantBuffer.append(event.stringifyContent());
                finalResponseReceived.set(true);
            }
        }).doOnComplete(() -> {
            if (finalResponseReceived.get()) {
                messageArchiveRepository.completeAssistantMessage(
                        conversationId, assistantMessageId, assistantBuffer.toString());
            } else {
                // 上游没有返回最终响应却结束时，不能把空或部分内容当作成功消息。
                messageArchiveRepository.updateAssistantMessageStatus(
                        conversationId, assistantMessageId, MessageStatus.FAILED);
            }
        }).doOnError(error -> messageArchiveRepository.updateAssistantMessageStatus(
                conversationId, assistantMessageId, MessageStatus.FAILED
        )).doOnCancel(() -> messageArchiveRepository.updateAssistantMessageStatus(
                conversationId, assistantMessageId, MessageStatus.CANCELLED
        ));
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {

        // 此入口可被未来的非 HTTP 适配器调用，不能只依赖 Web 层的 Bean Validation。
        validateInlineDataSize(chatCommandEntity);

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (aiAgentRegisterVO == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }

        String appName = aiAgentRegisterVO.getAppName();
        String userId = chatCommandEntity.getUserId();
        String sessionId = chatCommandEntity.getSessionId();

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchiveRepository.findOrCreateConversation(appName, chatCommandEntity.getUserId(), chatCommandEntity.getSessionId());

        // 构建参数
        List<Part> parts = new ArrayList<>();

        // 文本内容
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (texts != null) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
            // 保存用户文本消息
            String userText = texts.stream()
                    .map(ChatCommandEntity.Content.Text::getMessage)
                    .reduce("", (a, b) -> a + b);
            messageArchiveRepository.saveUserMessage(conversationId, appName, userId, sessionId, userText);
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
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        String content = event.stringifyContent();
                        outputs.add(content);
                        messageArchiveRepository.saveAssistantMessage(conversationId, appName, chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);
                    }
                });

        return outputs;
    }

    /**
     * 在构造 ADK Part 前检查内联字节总量。Bean Validation 适合 HTTP 绑定，但这里还要
     * 覆盖直接调用领域服务的场景，避免大字节数组已经交给模型 SDK 后才失败。
     */
    private void validateInlineDataSize(ChatCommandEntity chatCommandEntity) {
        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (inlineDatas == null) {
            return;
        }
        if (inlineDatas.size() > RequestPayloadLimits.MAX_INLINE_DATA_COUNT) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }

        long totalBytes = 0;
        for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
            if (inlineData == null || inlineData.getBytes() == null
                    || inlineData.getBytes().length > RequestPayloadLimits.MAX_INLINE_DATA_BYTES) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
            }
            totalBytes += inlineData.getBytes().length;
            if (totalBytes > RequestPayloadLimits.MAX_INLINE_DATA_TOTAL_BYTES) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
            }
        }
    }

    @Override
    public void deleteSession(String agentId, String userId, String sessionId) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (aiAgentRegisterVO == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        runner.sessionService()
                .deleteSession(appName, userId, sessionId)
                .blockingAwait();
    }
}
