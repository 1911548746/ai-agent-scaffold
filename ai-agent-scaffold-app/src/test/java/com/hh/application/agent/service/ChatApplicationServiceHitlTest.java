package com.hh.application.agent.service;

import com.hh.application.agent.model.ChatEvent;
import com.hh.application.agent.model.ChatStreamObserver;
import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.domain.agent.model.enums.MessageStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HITL-01：确认请求是正常暂停，不应被空响应分支标记为失败。 */
@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceHitlTest {

    @Mock
    private AgentRuntimePort agentRuntimePort;
    @Mock
    private ConversationArchivePort conversationArchivePort;
    @Mock
    private MessageArchivePort messageArchivePort;
    @Mock
    private ToolConfirmationPort toolConfirmationPort;

    @InjectMocks
    private ChatApplicationService chatService;

    @Test
    void confirmationOnlyStreamPersistsPendingRequestAndMarksMessageWaiting() {
        ToolConfirmationRequest request = new ToolConfirmationRequest(
                "confirmation-1", "adk-call-1", "invocation-1", "request_refund", "请确认退款");
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(agentRuntimePort.runStream("agent-a", "user-1", "session-1", "refund"))
                .thenReturn(observer -> {
                    observer.onEvent(ChatEvent.confirmation(request));
                    observer.onComplete();
                    return () -> { };
                });
        when(conversationArchivePort.findOrCreateConversation("app-a", "user-1", "session-1"))
                .thenReturn(1L);
        when(messageArchivePort.createPendingAssistantMessage(1L, "app-a", "user-1", "session-1"))
                .thenReturn(2L);

        chatService.handleMessageStream("agent-a", "user-1", "session-1", "refund")
                .subscribe(new ChatStreamObserver() {
                    @Override
                    public void onEvent(ChatEvent event) {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        verify(toolConfirmationPort).savePending(1L, "app-a", "user-1", "session-1", request);
        verify(messageArchivePort).updateAssistantMessageStatus(
                1L, 2L, MessageStatus.WAITING_CONFIRMATION);
    }
}
