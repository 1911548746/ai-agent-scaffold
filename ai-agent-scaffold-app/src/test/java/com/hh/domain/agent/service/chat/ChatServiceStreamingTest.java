package com.hh.domain.agent.service.chat;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import com.hh.domain.agent.adapter.repository.IConversationArchiveRepository;
import com.hh.domain.agent.adapter.repository.IMessageArchiveRepository;
import com.hh.domain.agent.model.valobj.AiAgentRegisterVO;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.service.armory.factory.DefaultArmoryFactory;
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
 * 流式消息状态机的离线回归测试，不创建 Spring Context，也不会连接模型或数据库。
 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStreamingTest {

    @Mock
    private DefaultArmoryFactory armoryFactory;
    @Mock
    private IConversationArchiveRepository conversationArchiveRepository;
    @Mock
    private IMessageArchiveRepository messageArchiveRepository;

    @InjectMocks
    private ChatService chatService;

    @Test
    void streamCompletedWithFinalResponse_marksAssistantMessageCompleted() {
        Runner runner = prepareRunner(Flowable.just(finalEvent("完整回复")));

        chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .blockingSubscribe();

        verify(messageArchiveRepository).createPendingAssistantMessage(1L, "app-a", "user-1", "session-1");
        verify(messageArchiveRepository).completeAssistantMessage(1L, 2L, "完整回复");
        verify(messageArchiveRepository, never()).updateAssistantMessageStatus(
                eq(1L), eq(2L), any(MessageStatus.class));
    }

    @Test
    void streamError_marksAssistantMessageFailedWithoutSavingPartialReply() {
        // 即使上游在给出最终片段后又失败，也不能把该片段误归档为 completed。
        Runner runner = prepareRunner(Flowable.concat(
                Flowable.just(finalEvent("未确认的片段")),
                Flowable.error(new IllegalStateException("provider failure"))
        ));

        assertThatThrownBy(() -> chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .blockingSubscribe())
                .isInstanceOf(IllegalStateException.class);

        verify(messageArchiveRepository).updateAssistantMessageStatus(1L, 2L, MessageStatus.FAILED);
        verify(messageArchiveRepository, never()).completeAssistantMessage(any(), any(), any());
    }

    @Test
    void streamCancelled_marksAssistantMessageCancelledWithoutSavingPartialReply() {
        Runner runner = prepareRunner(Flowable.never());

        Disposable subscription = chatService.handleMessageStream("agent-a", "user-1", "session-1", "hello")
                .subscribe();
        subscription.dispose();

        verify(messageArchiveRepository).updateAssistantMessageStatus(1L, 2L, MessageStatus.CANCELLED);
        verify(messageArchiveRepository, never()).completeAssistantMessage(any(), any(), any());
    }

    private Runner prepareRunner(Flowable<Event> events) {
        Runner runner = mock(Runner.class);
        when(armoryFactory.getAiAgentRegisterVO("agent-a"))
                .thenReturn(AiAgentRegisterVO.builder().appName("app-a").runner(runner).build());
        when(conversationArchiveRepository.findOrCreateConversation("app-a", "user-1", "session-1"))
                .thenReturn(1L);
        when(messageArchiveRepository.createPendingAssistantMessage(1L, "app-a", "user-1", "session-1"))
                .thenReturn(2L);
        when(runner.runAsync(eq("user-1"), eq("session-1"), any(Content.class), any(RunConfig.class)))
                .thenReturn(events);
        return runner;
    }

    private Event finalEvent(String content) {
        Event event = mock(Event.class);
        when(event.finalResponse()).thenReturn(true);
        when(event.stringifyContent()).thenReturn(content);
        return event;
    }
}
