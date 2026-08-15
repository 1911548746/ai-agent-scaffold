package com.hh.application.agent.service;

import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.domain.agent.model.entity.ChatCommandEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceMultimodalTest {

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
    void archivesAttachmentSummaryAndPendingConfirmationWithoutPersistingBytes() {
        ToolConfirmationRequest confirmation = new ToolConfirmationRequest(
                "confirmation-1", "call-1", "invocation-1", "request_refund", "请确认");
        ChatCommandEntity command = ChatCommandEntity.builder()
                .agentId("agent-a")
                .userId("user-1")
                .sessionId("session-1")
                .texts(List.of())
                .inlineDatas(List.of(new ChatCommandEntity.Content.InlineData(
                        new byte[]{1, 2, 3}, "image/png")))
                .build();
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(conversationArchivePort.findOrCreateConversation("app-a", "user-1", "session-1"))
                .thenReturn(1L);
        when(agentRuntimePort.run("agent-a", command))
                .thenReturn(new ChatResult(List.of(), List.of(confirmation)));

        chatService.handleMessage(command);

        verify(messageArchivePort).saveUserMessage(1L, "app-a", "user-1", "session-1", "用户上传了 1 个附件");
        verify(toolConfirmationPort).savePending(1L, "app-a", "user-1", "session-1", confirmation);
    }
}
