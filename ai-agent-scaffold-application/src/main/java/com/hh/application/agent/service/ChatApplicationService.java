package com.hh.application.agent.service;

import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ChatSubscription;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.application.agent.usecase.ChatUseCase;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.model.runtime.AgentDescriptor;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import com.hh.types.validation.RequestPayloadLimits;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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
    private final ToolConfirmationPort toolConfirmationPort;

    public ChatApplicationService(
            AgentRuntimePort agentRuntimePort,
            ConversationArchivePort conversationArchivePort,
            MessageArchivePort messageArchivePort,
            ToolConfirmationPort toolConfirmationPort) {
        this.agentRuntimePort = agentRuntimePort;
        this.conversationArchivePort = conversationArchivePort;
        this.messageArchivePort = messageArchivePort;
        this.toolConfirmationPort = toolConfirmationPort;
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
    public ChatResult handleMessage(String agentId, String userId, String sessionId, String message) {
        String appName = agentRuntimePort.appName(agentId);

        // 无效会话不能创建归档记录，否则会留下永远无法被 Runner 使用的孤立消息。
        agentRuntimePort.requireSession(agentId, userId, sessionId);

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);
        // 保存用户消息
        messageArchivePort.saveUserMessage(conversationId, appName, userId, sessionId, message);

        ChatResult result = agentRuntimePort.run(agentId, userId, sessionId, message);

        // 保存待确认的工具调用请求
        result.pendingConfirmations().forEach(request -> toolConfirmationPort.savePending(
                conversationId, appName, userId, sessionId, request));

        // 保存AI回复
        if (!result.contents().isEmpty()) {
            messageArchivePort.saveAssistantMessage(
                    conversationId, appName, userId, sessionId, result.joinContents());
        }
        return result;
    }

    @Override
    public ChatStream handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return handleMessageStream(
                agentId,
                userId,
                sessionId,
                message,
                () -> agentRuntimePort.runStream(agentId, userId, sessionId, message));
    }

    @Override
    public ChatResult handleMessage(ChatCommandEntity chatCommandEntity) {
        validateInlineDataSize(chatCommandEntity);
        validateMessageInput(chatCommandEntity);

        String appName = agentRuntimePort.appName(chatCommandEntity.getAgentId());
        String userId = chatCommandEntity.getUserId();
        String sessionId = chatCommandEntity.getSessionId();

        // 多模态入口与纯文本入口保持同一会话存在性约束。
        agentRuntimePort.requireSession(chatCommandEntity.getAgentId(), userId, sessionId);

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);

        // 文本内容；附件原始字节只进入本次 Runtime 调用，不写入消息表。
        messageArchivePort.saveUserMessage(conversationId, appName, userId, sessionId, archiveText(chatCommandEntity));

        ChatResult result = agentRuntimePort.run(chatCommandEntity.getAgentId(), chatCommandEntity);
        result.pendingConfirmations().forEach(request -> toolConfirmationPort.savePending(
                conversationId, appName, userId, sessionId, request));
        result.contents().forEach(content -> messageArchivePort.saveAssistantMessage(
                conversationId, appName, userId, sessionId, content));
        return result;
    }

    @Override
    public ChatStream handleMessageStream(ChatCommandEntity chatCommandEntity) {
        validateInlineDataSize(chatCommandEntity);
        validateMessageInput(chatCommandEntity);

        String agentId = chatCommandEntity.getAgentId();
        String userId = chatCommandEntity.getUserId();
        String sessionId = chatCommandEntity.getSessionId();
        String archiveText = archiveText(chatCommandEntity);

        return handleMessageStream(
                agentId,
                userId,
                sessionId,
                archiveText,
                () -> agentRuntimePort.runStream(agentId, chatCommandEntity));
    }


    /** 流式处理具体实现 */
    private ChatStream handleMessageStream(
            String agentId,
            String userId,
            String sessionId,
            String archiveText,
            Supplier<ChatStream> runtimeStreamSupplier) {
        String appName = agentRuntimePort.appName(agentId);

        // SSE 建立后只能发送 error 事件，因此必须在创建 pending 消息前完成会话校验。
        agentRuntimePort.requireSession(agentId, userId, sessionId);

        // 查找或创建会话记录，拿到 conversationId
        Long conversationId = conversationArchivePort.findOrCreateConversation(appName, userId, sessionId);
        // 保存用户消息
        messageArchivePort.saveUserMessage(conversationId, appName, userId, sessionId, archiveText);

        // 订阅前创建 pending 消息，避免取消或异常产生的片段被标记为成功回复。
        Long assistantMessageId = messageArchivePort.createPendingAssistantMessage(
                conversationId, appName, userId, sessionId);

        ChatStream runtimeStream = runtimeStreamSupplier.get();

        // 一个 pending 消息只能对应一次上游调用；重复订阅会造成重复归档或互相覆盖状态。
        AtomicBoolean subscribed = new AtomicBoolean(false);
        return observer -> {
            if (!subscribed.compareAndSet(false, true)) {
                throw new IllegalStateException("A chat stream can only be subscribed once");
            }

            StringBuilder assistantBuffer = new StringBuilder();
            AtomicBoolean responseReceived = new AtomicBoolean(false);
            AtomicBoolean confirmationReceived = new AtomicBoolean(false);
            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicReference<ChatSubscription> upstreamReference = new AtomicReference<>();

            ChatSubscription upstream = runtimeStream.subscribe(new ChatStreamObserver() {
                @Override
                public void onEvent(ChatEvent event) {
                    // infrastructure 已过滤 SDK 内部事件；application 只归档可展示的助手文本。
                    if (!terminal.get()) {
                        if (event.isToolConfirmation()) {
                            toolConfirmationPort.savePending(
                                    conversationId, appName, userId, sessionId, event.confirmation());
                            confirmationReceived.set(true);
                            observer.onEvent(event);
                            return;
                        }
                        assistantBuffer.append(event.content());
                        responseReceived.set(true);
                        observer.onEvent(event);
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (terminal.compareAndSet(false, true)) {
                        messageArchivePort.updateAssistantMessageStatus(
                                conversationId, assistantMessageId, MessageStatus.FAILED);
                        observer.onError(error);
                    }
                }

                @Override
                public void onComplete() {
                    if (terminal.compareAndSet(false, true)) {
                        if (responseReceived.get()) {
                            messageArchivePort.completeAssistantMessage(
                                    conversationId, assistantMessageId, assistantBuffer.toString());
                        } else if (confirmationReceived.get()) {
                            // 等待人工确认是正常暂停，不应被当前空响应分支标记为失败。
                            messageArchivePort.updateAssistantMessageStatus(
                                    conversationId, assistantMessageId, MessageStatus.WAITING_CONFIRMATION);
                        } else {
                            // 没有任何可展示响应时不能把空消息标记为完成。
                            messageArchivePort.updateAssistantMessageStatus(
                                    conversationId, assistantMessageId, MessageStatus.FAILED);
                        }
                        observer.onComplete();
                    }
                }
            });
            upstreamReference.set(upstream);

            return () -> {
                if (terminal.compareAndSet(false, true)) {
                    // 先落库再取消上游，确保取消回调与上游终止信号竞争时状态仍可预期。
                    messageArchivePort.updateAssistantMessageStatus(
                            conversationId, assistantMessageId, MessageStatus.CANCELLED);
                    ChatSubscription currentUpstream = upstreamReference.get();
                    if (currentUpstream != null) {
                        currentUpstream.cancel();
                    }
                }
            };
        };
    }


    /** 根据 ChatCommandEntity 生成用户输入摘要 */
    private String archiveText(ChatCommandEntity command) {

        List<ChatCommandEntity.Content.Text> texts = command.getTexts();

        String userText = texts == null ? "" : texts.stream()
                .filter(text -> text != null && text.getMessage() != null)
                .map(ChatCommandEntity.Content.Text::getMessage)
                .reduce("", String::concat);

        // 如果用户输入了文本，则返回用户文本
        if (!userText.isBlank()) {
            return userText;
        }

        // 如果用户没有输入文本，则组装一段描述性文本作为用户输入返回
        int attachmentCount = (command.getInlineDatas() == null ? 0 : command.getInlineDatas().size())
                + (command.getFiles() == null ? 0 : command.getFiles().size());
        return "用户上传了 " + attachmentCount + " 个附件";
    }

    /**
     * 检查 InlineData 大小，覆盖绕过 HTTP Bean Validation 的直接调用。
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

    /** 检查 ChatCommandEntity 内容, 至少需要一段文本或一个附件，避免构造空的 ADK Content。 */
    private void validateMessageInput(ChatCommandEntity chatCommandEntity) {
        boolean hasText = chatCommandEntity.getTexts() != null
                && chatCommandEntity.getTexts().stream()
                .anyMatch(text -> text != null && text.getMessage() != null && !text.getMessage().isBlank());
        boolean hasInlineData = chatCommandEntity.getInlineDatas() != null
                && chatCommandEntity.getInlineDatas().stream()
                .anyMatch(data -> data != null && data.getBytes() != null && data.getBytes().length > 0);
        boolean hasFileReference = chatCommandEntity.getFiles() != null
                && !chatCommandEntity.getFiles().isEmpty();
        if (!hasText && !hasInlineData && !hasFileReference) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "message或files至少提供一项");
        }
    }

}
