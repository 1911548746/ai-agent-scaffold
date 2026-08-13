package com.hh.application.agent.service;

import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatStream;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ChatSubscription;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式消息状态机的离线回归测试；运行时事件由端口 fake 提供，不创建模型连接。
 */
@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceStreamingTest {

    @Mock
    private AgentRuntimePort agentRuntimePort;
    @Mock
    private ConversationArchivePort conversationArchivePort;
    @Mock
    private MessageArchivePort messageArchivePort;

    @InjectMocks
    private ChatApplicationService chatService;

    @Test
    void streamCompletedWithFinalResponse_marksAssistantMessageCompleted() {
        prepareStream(observer -> {
            observer.onEvent(new ChatEvent("完整回复"));
            observer.onComplete();
            return () -> { };
        });

        chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .subscribe(noOpObserver());

        verify(messageArchivePort).completeAssistantMessage(1L, 2L, "完整回复");
        verify(messageArchivePort, never()).updateAssistantMessageStatus(
                eq(1L), eq(2L), any(MessageStatus.class));
    }

    @Test
    void streamError_marksAssistantMessageFailedWithoutSavingPartialReply() {
        prepareStream(observer -> {
            observer.onEvent(new ChatEvent("未确认的片段"));
            observer.onError(new IllegalStateException("provider failure"));
            return () -> { };
        });

        chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .subscribe(noOpObserver());

        verify(messageArchivePort).updateAssistantMessageStatus(1L, 2L, MessageStatus.FAILED);
        verify(messageArchivePort, never()).completeAssistantMessage(any(), any(), any());
    }

    @Test
    void streamCancelled_marksAssistantMessageCancelledWithoutSavingPartialReply() {
        AtomicInteger upstreamCancellations = new AtomicInteger();
        prepareStream(observer -> upstreamCancellations::incrementAndGet);

        ChatSubscription subscription = chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .subscribe(noOpObserver());
        subscription.cancel();

        // application 取消后必须继续传递到 runtime port，不能只更新归档状态。
        assertThat(upstreamCancellations).hasValue(1);
        verify(messageArchivePort).updateAssistantMessageStatus(1L, 2L, MessageStatus.CANCELLED);
        verify(messageArchivePort, never()).completeAssistantMessage(any(), any(), any());
    }

    @Test
    void streamRejectsMissingSessionBeforeCreatingPendingArchives() {
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        org.mockito.Mockito.doThrow(new AppException(ResponseCode.SESSION_NOT_FOUND))
                .when(agentRuntimePort).requireSession("agent-a", "user-1", "missing-session");

        assertThatThrownBy(() -> chatService.handleMessageStream(
                "agent-a", "user-1", "missing-session", "hello"))
                .isInstanceOf(AppException.class)
                .extracting("code")
                .isEqualTo(ResponseCode.SESSION_NOT_FOUND.getCode());

        verify(conversationArchivePort, never()).findOrCreateConversation(any(), any(), any());
        verify(messageArchivePort, never()).saveUserMessage(any(), any(), any(), any(), any());
        verify(messageArchivePort, never()).createPendingAssistantMessage(any(), any(), any(), any());
        verify(agentRuntimePort, never()).runStream(any(), any(), any(), any());
    }

    private void prepareStream(ChatStream stream) {
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(agentRuntimePort.runStream("agent-a", "user-1", "session-1", "hello")).thenReturn(stream);
        when(conversationArchivePort.findOrCreateConversation("app-a", "user-1", "session-1"))
                .thenReturn(1L);
        when(messageArchivePort.createPendingAssistantMessage(1L, "app-a", "user-1", "session-1"))
                .thenReturn(2L);
    }

    private ChatStreamObserver noOpObserver() {
        return new ChatStreamObserver() {
            @Override
            public void onEvent(ChatEvent event) {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        };
    }
}
