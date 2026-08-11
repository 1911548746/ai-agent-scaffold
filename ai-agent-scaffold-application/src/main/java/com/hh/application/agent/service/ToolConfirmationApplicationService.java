package com.hh.application.agent.service;

import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ChatSubscription;
import com.hh.application.agent.model.ToolConfirmationRecord;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.application.agent.usecase.ToolConfirmationUseCase;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.model.enums.ToolConfirmationStatus;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 工具确认后的恢复编排。
 *
 * <p>确认单先以条件更新领取，再调用同一 Agent/用户/Session。调用失败时不自动重放，
 * 状态进入 {@code execution_unknown}，避免高风险工具被重复执行。</p>
 */
public class ToolConfirmationApplicationService implements ToolConfirmationUseCase {

    private final ToolConfirmationPort confirmationPort;
    private final AgentRuntimePort agentRuntimePort;
    private final ConversationArchivePort conversationArchivePort;
    private final MessageArchivePort messageArchivePort;

    public ToolConfirmationApplicationService(
            ToolConfirmationPort confirmationPort,
            AgentRuntimePort agentRuntimePort,
            ConversationArchivePort conversationArchivePort,
            MessageArchivePort messageArchivePort) {
        this.confirmationPort = confirmationPort;
        this.agentRuntimePort = agentRuntimePort;
        this.conversationArchivePort = conversationArchivePort;
        this.messageArchivePort = messageArchivePort;
    }

    @Override
    public ToolConfirmationRecord get(String confirmationId, String userId) {
        // 当前阶段仍由请求传入 userId；后续 SEC-04 接入认证上下文后，应由认证主体替代该参数。
        return find(confirmationId, userId);
    }

    /**
     * 以普通 HTTP 响应恢复一次等待确认的工具调用。
     *
     * <p>恢复不是重发用户自然语言，而是向原 Session 写入 ADK {@code FunctionResponse}。因此必须先
     * 原子领取确认单，防止用户双击或多个应用节点同时触发同一笔有副作用的工具调用。</p>
     */
    @Override
    public ChatResult resolve(String confirmationId, String agentId, String userId, boolean confirmed) {
        ToolConfirmationRecord confirmation = claim(confirmationId, agentId, userId);
        Long conversationId = conversationArchivePort.findOrCreateConversation(
                confirmation.appName(), userId, confirmation.sessionId());
        Long assistantMessageId;
        try {
            // 恢复后生成的 assistant 内容必须独立归档，不能追加到首次暂停前的回复中。
            assistantMessageId = messageArchivePort.createPendingAssistantMessage(
                    conversationId, confirmation.appName(), userId, confirmation.sessionId());
        } catch (RuntimeException | Error error) {
            // 已领取但还未进入 Runner 时发生持久化错误，不能安全地让调用方直接重试该确认。
            markExecutionUnknown(confirmation);
            throw error;
        }
        try {
            // application 只传递 opaque call id；ADK FunctionResponse 的 SDK 组装封装在 infrastructure。
            ChatResult result = agentRuntimePort.runConfirmation(
                    agentId, userId, confirmation.sessionId(), confirmation.adkCallId(), confirmed);
            persistResult(conversationId, assistantMessageId, confirmation, result);
            // 当前确认单结束不代表任务不再暂停：Runner 仍可能返回下一张待确认单。
            finishOrFail(confirmation, confirmed ? ToolConfirmationStatus.COMPLETED
                    : ToolConfirmationStatus.REJECTED);
            return result;
        } catch (RuntimeException | Error error) {
            // Runner 可能已执行工具但调用方未取得完整结果，保守标记 unknown，禁止自动重放。
            messageArchivePort.updateAssistantMessageStatus(
                    conversationId, assistantMessageId, MessageStatus.FAILED);
            markExecutionUnknown(confirmation);
            throw error;
        }
    }

    /**
     * 以 SSE 恢复任务。
     *
     * <p>原始 SSE 已在首次确认时正常结束；本方法创建新的项目流与新的 Runner 调用。流式文本在
     * {@code onComplete} 才归档为 completed，防止取消或异常时把片段误记为完整回复。</p>
     */
    @Override
    public ChatStream resolveStream(
            String confirmationId,
            String agentId,
            String userId,
            boolean confirmed) {
        ToolConfirmationRecord confirmation = claim(confirmationId, agentId, userId);
        Long conversationId = conversationArchivePort.findOrCreateConversation(
                confirmation.appName(), userId, confirmation.sessionId());
        Long assistantMessageId;
        try {
            // 先建立独立 pending 消息，使后续 complete、failed、cancelled 具备稳定更新目标。
            assistantMessageId = messageArchivePort.createPendingAssistantMessage(
                    conversationId, confirmation.appName(), userId, confirmation.sessionId());
        } catch (RuntimeException | Error error) {
            markExecutionUnknown(confirmation);
            throw error;
        }
        ChatStream runtimeStream;
        try {
            // 这里只创建惰性流；实际 Runner 调用发生在下游订阅时。
            runtimeStream = agentRuntimePort.runConfirmationStream(
                    agentId, userId, confirmation.sessionId(), confirmation.adkCallId(), confirmed);
        } catch (RuntimeException | Error error) {
            messageArchivePort.updateAssistantMessageStatus(
                    conversationId, assistantMessageId, MessageStatus.FAILED);
            markExecutionUnknown(confirmation);
            throw error;
        }
        AtomicBoolean subscribed = new AtomicBoolean(false);
        return observer -> {
            if (!subscribed.compareAndSet(false, true)) {
                // 二次订阅会重复执行确认后的工具调用，因此该项目流只能消费一次。
                throw new IllegalStateException("A confirmation stream can only be subscribed once");
            }
            List<String> content = new ArrayList<>();
            AtomicBoolean hasConfirmation = new AtomicBoolean(false);
            AtomicBoolean terminal = new AtomicBoolean(false);
            AtomicReference<ChatSubscription> upstreamReference = new AtomicReference<>();
            ChatSubscription upstream;
            try {
                upstream = runtimeStream.subscribe(new ChatStreamObserver() {
                    @Override
                    public void onEvent(ChatEvent event) {
                        if (terminal.get()) {
                            // cancel/error 与上游回调并发到达时，终态后的内容不能再写入或转发。
                            return;
                        }
                        if (event.isToolConfirmation()) {
                            // 一个恢复任务可能再次要求人工确认，必须先持久化再通知客户端。
                            confirmationPort.savePending(
                                    conversationId, confirmation.appName(), userId,
                                    confirmation.sessionId(), event.confirmation());
                            hasConfirmation.set(true);
                        } else {
                            // ChatEvent 已由 runtime 过滤 ADK 控制事件，此处只积累可展示文本。
                            content.add(event.content());
                        }
                        observer.onEvent(event);
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (terminal.compareAndSet(false, true)) {
                            messageArchivePort.updateAssistantMessageStatus(
                                    conversationId, assistantMessageId, MessageStatus.FAILED);
                            // 异常发生时无法判断外部工具是否已执行完成，不能把确认单退回 pending。
                            markExecutionUnknown(confirmation);
                            observer.onError(error);
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (terminal.compareAndSet(false, true)) {
                            String text = String.join("", content);
                            if (!text.isBlank()) {
                                messageArchivePort.completeAssistantMessage(
                                        conversationId, assistantMessageId, text);
                            } else if (hasConfirmation.get()) {
                                // 当前恢复正常暂停，等待下一次确认；空 assistant 消息不应标记失败。
                                messageArchivePort.updateAssistantMessageStatus(
                                        conversationId, assistantMessageId,
                                        MessageStatus.WAITING_CONFIRMATION);
                            } else {
                                // 无文本的正常结束仍是已完成调用，保留空消息与会话顺序一致。
                                messageArchivePort.completeAssistantMessage(
                                        conversationId, assistantMessageId, "");
                            }
                            finishOrFail(confirmation, confirmed
                                    ? ToolConfirmationStatus.COMPLETED
                                    : ToolConfirmationStatus.REJECTED);
                            observer.onComplete();
                        }
                    }
                });
            } catch (RuntimeException | Error error) {
                // subscribe 本身也可能同步失败；此时尚未收到 onError，必须在这里完成补偿。
                terminal.set(true);
                messageArchivePort.updateAssistantMessageStatus(
                        conversationId, assistantMessageId, MessageStatus.FAILED);
                markExecutionUnknown(confirmation);
                throw error;
            }
            upstreamReference.set(upstream);
            return () -> {
                if (terminal.compareAndSet(false, true)) {
                    // 先持久化取消状态，再取消上游；避免上游同步回调覆盖本次取消结果。
                    messageArchivePort.updateAssistantMessageStatus(
                            conversationId, assistantMessageId, MessageStatus.CANCELLED);
                    markExecutionUnknown(confirmation);
                    ChatSubscription current = upstreamReference.get();
                    if (current != null) {
                        current.cancel();
                    }
                }
            };
        };
    }

    /**
     * 校验确认单归属并原子领取。
     *
     * <p>先比对请求 Agent 对应的 appName，避免客户端用其他 Agent 的接口恢复该 Session；随后用
     * {@code status = pending} 条件更新建立真正的并发仲裁，不能只依赖前面的查询结果。</p>
     */
    private ToolConfirmationRecord claim(String confirmationId, String agentId, String userId) {
        ToolConfirmationRecord confirmation = find(confirmationId, userId);
        String appName = agentRuntimePort.appName(agentId);
        if (!appName.equals(confirmation.appName())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }
        if (confirmation.status() != ToolConfirmationStatus.PENDING
                || !confirmationPort.claimPending(confirmationId, userId, appName)) {
            throw new AppException(ResponseCode.TOOL_CONFIRMATION_NOT_PENDING);
        }
        return confirmation;
    }

    /** 查询时同时按 confirmationId 与当前用户过滤，防止 ID 被猜中后跨用户读取。 */
    private ToolConfirmationRecord find(String confirmationId, String userId) {
        return confirmationPort.find(confirmationId, userId)
                .orElseThrow(() -> new AppException(ResponseCode.TOOL_CONFIRMATION_NOT_FOUND));
    }

    /**
     * 将恢复调用的新结果归档。
     *
     * <p>若任务再次暂停，新的确认单必须先入库，随后把本轮 assistant 消息停在
     * {@link MessageStatus#WAITING_CONFIRMATION}；否则客户端断线后将无法找回下一次审批入口。</p>
     */
    private void persistResult(
            Long conversationId,
            Long assistantMessageId,
            ToolConfirmationRecord confirmation,
            ChatResult result) {
        result.pendingConfirmations().forEach(request -> confirmationPort.savePending(
                conversationId,
                confirmation.appName(),
                confirmation.userId(),
                confirmation.sessionId(),
                request));
        if (!result.contents().isEmpty()) {
            messageArchivePort.completeAssistantMessage(
                    conversationId, assistantMessageId, result.joinContents());
        } else if (!result.pendingConfirmations().isEmpty()) {
            messageArchivePort.updateAssistantMessageStatus(
                    conversationId, assistantMessageId, MessageStatus.WAITING_CONFIRMATION);
        } else {
            messageArchivePort.completeAssistantMessage(conversationId, assistantMessageId, "");
        }
    }

    /** 只有仍处于 processing 的确认单才能进入确定终态，更新失败按执行状态未知处理。 */
    private void finishOrFail(ToolConfirmationRecord confirmation, ToolConfirmationStatus status) {
        if (!confirmationPort.finish(confirmation.confirmationId(), confirmation.userId(), status)
        ) {
            throw new AppException(ResponseCode.TOOL_CONFIRMATION_EXECUTION_UNKNOWN);
        }
    }

    /**
     * 发生异常、取消或持久化故障后保守收尾。
     *
     * <p>不将确认单退回 pending，因为无法证明工具没有产生外部副作用；后续 HITL-03 应提供人工
     * 核验、审计与幂等键支撑的处置流程。</p>
     */
    private void markExecutionUnknown(ToolConfirmationRecord confirmation) {
        confirmationPort.finish(
                confirmation.confirmationId(), confirmation.userId(),
                ToolConfirmationStatus.EXECUTION_UNKNOWN);
    }
}
