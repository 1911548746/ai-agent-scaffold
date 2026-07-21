package com.hh.application.agent.service;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.domain.agent.adapter.repository.IConversationArchiveRepository;
import com.hh.domain.agent.adapter.repository.IMessageArchiveRepository;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.runtime.AgentRuntimeRegistry;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.validation.RequestPayloadLimits;
import io.reactivex.rxjava3.core.Flowable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话用例编排。
 *
 * <p>该类不使用 Spring 注解，依赖由 app 层显式提供，便于离线测试替换为 fake 或 mock。
 * ADK 的运行时调用将在 ARC-05 迁移至 infrastructure runtime port。</p>
 */
public class ChatApplicationService implements ChatUseCase {

    private final AgentRuntimeRegistry runtimeRegistry;
    private final IConversationArchiveRepository conversationArchiveRepository;
    private final IMessageArchiveRepository messageArchiveRepository;

    public ChatApplicationService(
            AgentRuntimeRegistry runtimeRegistry,
            IConversationArchiveRepository conversationArchiveRepository,
            IMessageArchiveRepository messageArchiveRepository) {
        this.runtimeRegistry = runtimeRegistry;
        this.conversationArchiveRepository = conversationArchiveRepository;
        this.messageArchiveRepository = messageArchiveRepository;
    }

    @Override
    public List<AgentDescriptor> queryAgentList() {
        List<AgentDescriptor> agentList = new ArrayList<>();

        // Registry 快照不可变，列表查询不会与启动发布过程交叉看到半成品配置。
        for (AgentRuntime runtime : runtimeRegistry.snapshot().values()) {
            agentList.add(runtime.toAgentDescriptor());
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AgentRuntime runtime = requireRuntime(agentId);
        String appName = runtime.appName();
        Runner runner = runtime.runner();

        // 会话身份由 appName、userId、sessionId 共同确定，不能按 userId 缓存。
        // 否则同一用户切换 Agent 或创建第二个会话时会错误复用旧会话。
        Session session = runner.sessionService()
                .createSession(appName, userId)
                .blockingGet();
        String sessionId = session.id();

        // 创建对应归档记录；后续对话必须携带调用方明确传入的 sessionId。
        conversationArchiveRepository.findOrCreateConversation(appName, userId, sessionId);
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        AgentRuntime runtime = requireRuntime(agentId);
        String appName = runtime.appName();
        Runner runner = runtime.runner();

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

        // 保存AI回复
        messageArchiveRepository.saveAssistantMessage(
                conversationId, appName, userId, sessionId, String.join("\n", outputs));
        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AgentRuntime runtime = requireRuntime(agentId);
        RunConfig runConfig = RunConfig.builder()
                .streamingMode(RunConfig.StreamingMode.SSE)
                .build();

        String appName = runtime.appName();
        Runner runner = runtime.runner();

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchiveRepository.findOrCreateConversation(appName, userId, sessionId);
        // 保存用户消息
        messageArchiveRepository.saveUserMessage(conversationId, appName, userId, sessionId, message);

        // 订阅前创建 pending 消息，避免取消或异常产生的片段被标记为成功回复。
        Long assistantMessageId = messageArchiveRepository.createPendingAssistantMessage(
                conversationId, appName, userId, sessionId);

        // TODO 待解决：流式输出时会存在 events 表中一个 token 存一条记录
        Flowable<Event> events = runner.runAsync(
                userId, sessionId, Content.fromParts(Part.fromText(message)), runConfig);

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
        validateInlineDataSize(chatCommandEntity);

        AgentRuntime runtime = requireRuntime(chatCommandEntity.getAgentId());
        String appName = runtime.appName();
        String userId = chatCommandEntity.getUserId();
        String sessionId = chatCommandEntity.getSessionId();

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchiveRepository.findOrCreateConversation(appName, userId, sessionId);

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
                    .reduce("", (left, right) -> left + right);
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

        List<String> outputs = new ArrayList<>();
        runtime.runner().runAsync(userId, sessionId, Content.builder().parts(parts).build())
                .blockingForEach(event -> {
                    if (event.finalResponse()) {
                        String content = event.stringifyContent();
                        outputs.add(content);
                        messageArchiveRepository.saveAssistantMessage(
                                conversationId, appName, userId, sessionId, content);
                    }
                });
        return outputs;
    }

    /**
     * 在构造 ADK Part 前检查内联字节总量，覆盖绕过 HTTP Bean Validation 的直接调用。
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
        AgentRuntime runtime = requireRuntime(agentId);
        runtime.runner().sessionService()
                .deleteSession(runtime.appName(), userId, sessionId)
                .blockingAwait();
    }

    /**
     * 所有请求统一从 Registry 查找已发布快照，缺失时返回稳定业务错误而不是 Bean 查找异常。
     */
    private AgentRuntime requireRuntime(String agentId) {
        return runtimeRegistry.find(agentId)
                .orElseThrow(() -> new AppException(ResponseCode.AGENT_NOT_FOUND));
    }
}
