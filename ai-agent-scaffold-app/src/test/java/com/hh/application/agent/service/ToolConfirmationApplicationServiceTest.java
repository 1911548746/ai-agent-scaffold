package com.hh.application.agent.service;

import com.hh.application.agent.model.ChatResult;
import com.hh.application.agent.model.ToolConfirmationRecord;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.domain.agent.model.enums.ToolConfirmationStatus;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HITL-02：确认领取必须幂等，恢复结果作为独立 assistant 消息归档。 */
class ToolConfirmationApplicationServiceTest {

    @Test
    void resolvesOnSameSessionAndFinishesApprovedConfirmation() {
        ToolConfirmationPort confirmationPort = mock(ToolConfirmationPort.class);
        AgentRuntimePort runtimePort = mock(AgentRuntimePort.class);
        ConversationArchivePort conversationPort = mock(ConversationArchivePort.class);
        MessageArchivePort messagePort = mock(MessageArchivePort.class);
        ToolConfirmationRecord record = record(ToolConfirmationStatus.PENDING);
        when(confirmationPort.find("confirmation-1", "user-1")).thenReturn(Optional.of(record));
        when(confirmationPort.claimPending("confirmation-1", "user-1", "app-a")).thenReturn(true);
        when(runtimePort.appName("agent-a")).thenReturn("app-a");
        when(conversationPort.findOrCreateConversation("app-a", "user-1", "session-1"))
                .thenReturn(7L);
        when(messagePort.createPendingAssistantMessage(7L, "app-a", "user-1", "session-1"))
                .thenReturn(8L);
        when(runtimePort.runConfirmation("agent-a", "user-1", "session-1", "adk-call-1", true))
                .thenReturn(new ChatResult(java.util.List.of("退款已完成")));
        when(confirmationPort.finish("confirmation-1", "user-1", ToolConfirmationStatus.COMPLETED))
                .thenReturn(true);

        new ToolConfirmationApplicationService(
                confirmationPort, runtimePort, conversationPort, messagePort)
                .resolve("confirmation-1", "agent-a", "user-1", true);

        verify(runtimePort).runConfirmation("agent-a", "user-1", "session-1", "adk-call-1", true);
        verify(messagePort).completeAssistantMessage(7L, 8L, "退款已完成");
        verify(confirmationPort).finish("confirmation-1", "user-1", ToolConfirmationStatus.COMPLETED);
    }

    @Test
    void refusesSecondDecisionWithoutRunningAgentAgain() {
        ToolConfirmationPort confirmationPort = mock(ToolConfirmationPort.class);
        AgentRuntimePort runtimePort = mock(AgentRuntimePort.class);
        when(confirmationPort.find("confirmation-1", "user-1"))
                .thenReturn(Optional.of(record(ToolConfirmationStatus.COMPLETED)));
        when(runtimePort.appName("agent-a")).thenReturn("app-a");

        assertThatThrownBy(() -> new ToolConfirmationApplicationService(
                confirmationPort, runtimePort, mock(ConversationArchivePort.class),
                mock(MessageArchivePort.class))
                .resolve("confirmation-1", "agent-a", "user-1", true))
                .isInstanceOf(AppException.class);
    }

    private ToolConfirmationRecord record(ToolConfirmationStatus status) {
        return new ToolConfirmationRecord(
                "confirmation-1", 7L, "app-a", "user-1", "session-1", "invocation-1",
                "adk-call-1", "refund", "请确认退款", status,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
