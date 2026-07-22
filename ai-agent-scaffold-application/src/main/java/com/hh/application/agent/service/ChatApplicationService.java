package com.hh.application.agent.service;

import com.google.adk.events.Event;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.validation.RequestPayloadLimits;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话用例编排。
 *
 * <p>该类不使用 Spring 注解，依赖由 app 层显式提供，便于离线测试替换为 fake 或 mock。
 * ADK 的运行时调用已通过 infrastructure runtime port 隔离。</p>
 */
public class ChatApplicationService implements ChatUseCase {

    private final AgentRuntimePort agentRuntimePort;
    private final ConversationArchivePort conversationArchivePort;
    private final MessageArchivePort messageArchivePort;

    public ChatApplicationService(
            AgentRuntimePort agentRuntimePort,
            ConversationArchivePort conversationArchivePort,
            MessageArchivePort messageArchivePort) {
        this.agentRuntimePort = agentRuntimePort;
        this.conversationArchivePort = conversationArchivePort;
        this.messageArchivePort = messageArchivePort;
    }

    @Override
    public List<AgentDescriptor> queryAgentList() {
        // Runtime Port 只返回展示元数据，application 不会接触 ADK Runner 快照。
        return agentRuntimePort.listAgents();
    }

    @Override
    public String createSession(String agentId, String userId) {
        // 会话身份由 appName、userId、sessionId 共同确定，不能按 userId 缓存。
        // 否则同一用户切换 Agent 或创建第二个会话时会错误复用旧会话。
        String appName = agentRuntimePort.appName(agentId);
        String sessionId = agentRuntimePort.createSession(agentId, userId);

        // 创建对应归档记录；后续对话必须携带调用方明确传入的 sessionId。
        conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        String appName = agentRuntimePort.appName(agentId);

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);
        // 保存用户消息
        messageArchivePort.saveUserMessage(conversationId, appName, userId, sessionId, message);

        List<String> outputs = agentRuntimePort.run(agentId, userId, sessionId, message);

        // 保存AI回复
        messageArchivePort.saveAssistantMessage(
                conversationId, appName, userId, sessionId, String.join("\n", outputs));
        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        String appName = agentRuntimePort.appName(agentId);

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);
        // 保存用户消息
        messageArchivePort.saveUserMessage(conversationId, appName, userId, sessionId, message);

        // 订阅前创建 pending 消息，避免取消或异常产生的片段被标记为成功回复。
        Long assistantMessageId = messageArchivePort.createPendingAssistantMessage(
                conversationId, appName, userId, sessionId);

        // TODO 待解决：流式输出时会存在 events 表中一个 token 存一条记录
        Flowable<Event> events = agentRuntimePort.runStream(agentId, userId, sessionId, message);

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
                messageArchivePort.completeAssistantMessage(
                        conversationId, assistantMessageId, assistantBuffer.toString());
            } else {
                // 上游没有返回最终响应却结束时，不能把空或部分内容当作成功消息。
                messageArchivePort.updateAssistantMessageStatus(
                        conversationId, assistantMessageId, MessageStatus.FAILED);
            }
        }).doOnError(error -> messageArchivePort.updateAssistantMessageStatus(
                conversationId, assistantMessageId, MessageStatus.FAILED
        )).doOnCancel(() -> messageArchivePort.updateAssistantMessageStatus(
                conversationId, assistantMessageId, MessageStatus.CANCELLED
        ));
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        validateInlineDataSize(chatCommandEntity);

        String appName = agentRuntimePort.appName(chatCommandEntity.getAgentId());
        String userId = chatCommandEntity.getUserId();
        String sessionId = chatCommandEntity.getSessionId();

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);

        // 文本内容
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (texts != null) {
            // 保存用户文本消息
            String userText = texts.stream()
                    .map(ChatCommandEntity.Content.Text::getMessage)
                    .reduce("", (left, right) -> left + right);
            messageArchivePort.saveUserMessage(conversationId, appName, userId, sessionId, userText);
        }

        List<String> outputs = agentRuntimePort.run(chatCommandEntity.getAgentId(), chatCommandEntity);
        outputs.forEach(content -> messageArchivePort.saveAssistantMessage(
                conversationId, appName, userId, sessionId, content));
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
        agentRuntimePort.deleteSession(agentId, userId, sessionId);
    }
}
