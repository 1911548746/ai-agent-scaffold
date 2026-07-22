package com.hh.application.agent.service;

import com.google.adk.events.Event;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.domain.agent.model.enums.MessageStatus;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        prepareStream(Flowable.just(finalEvent("完整回复")));

        chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .blockingSubscribe();

        verify(messageArchivePort).completeAssistantMessage(1L, 2L, "完整回复");
        verify(messageArchivePort, never()).updateAssistantMessageStatus(
                eq(1L), eq(2L), any(MessageStatus.class));
    }

    @Test
    void streamError_marksAssistantMessageFailedWithoutSavingPartialReply() {
        prepareStream(Flowable.concat(
                Flowable.just(finalEvent("未确认的片段")),
                Flowable.error(new IllegalStateException("provider failure"))));

        assertThatThrownBy(() -> chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .blockingSubscribe())
                .isInstanceOf(IllegalStateException.class);

        verify(messageArchivePort).updateAssistantMessageStatus(1L, 2L, MessageStatus.FAILED);
        verify(messageArchivePort, never()).completeAssistantMessage(any(), any(), any());
    }

    @Test
    void streamCancelled_marksAssistantMessageCancelledWithoutSavingPartialReply() {
        prepareStream(Flowable.never());

        Disposable subscription = chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .subscribe();
        subscription.dispose();

        verify(messageArchivePort).updateAssistantMessageStatus(1L, 2L, MessageStatus.CANCELLED);
        verify(messageArchivePort, never()).completeAssistantMessage(any(), any(), any());
    }

    private void prepareStream(Flowable<Event> events) {
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(agentRuntimePort.runStream("agent-a", "user-1", "session-1", "hello")).thenReturn(events);
        when(conversationArchivePort.findOrCreateConversation("app-a", "user-1", "session-1"))
                .thenReturn(1L);
        when(messageArchivePort.createPendingAssistantMessage(1L, "app-a", "user-1", "session-1"))
                .thenReturn(2L);
    }

    private Event finalEvent(String content) {
        Event event = mock(Event.class);
        when(event.finalResponse()).thenReturn(true);
        when(event.stringifyContent()).thenReturn(content);
        return event;
    }
}
