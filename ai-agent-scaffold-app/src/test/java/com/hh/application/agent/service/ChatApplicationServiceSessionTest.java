package com.hh.application.agent.service;

import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.MessageArchivePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话用例只验证编排逻辑；ADK SessionService 细节由 infrastructure runtime adapter 负责。
 */
@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceSessionTest {

    @Mock
    private AgentRuntimePort agentRuntimePort;
    @Mock
    private ConversationArchivePort conversationArchivePort;
    @Mock
    private MessageArchivePort messageArchivePort;

    @InjectMocks
    private ChatApplicationService chatService;

    @Test
    void createSession_sameUserAndAgent_archivesEachExplicitSession() {
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(agentRuntimePort.createSession("agent-a", "user-1"))
                .thenReturn("session-1", "session-2");

        String firstSessionId = chatService.createSession("agent-a", "user-1");
        String secondSessionId = chatService.createSession("agent-a", "user-1");

        // 每次显式创建的 ID 都要独立归档，不能由 application 以 userId 缓存复用。
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);
        verify(agentRuntimePort, times(2)).createSession("agent-a", "user-1");
        verify(conversationArchivePort).findOrCreateConversation("app-a", "user-1", firstSessionId);
        verify(conversationArchivePort).findOrCreateConversation("app-a", "user-1", secondSessionId);
    }

    @Test
    void createSession_sameUserAcrossAgents_usesEachAppScope() {
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(agentRuntimePort.appName("agent-b")).thenReturn("app-b");
        when(agentRuntimePort.createSession("agent-a", "user-1")).thenReturn("session-a");
        when(agentRuntimePort.createSession("agent-b", "user-1")).thenReturn("session-b");

        assertThat(chatService.createSession("agent-a", "user-1")).isEqualTo("session-a");
        assertThat(chatService.createSession("agent-b", "user-1")).isEqualTo("session-b");

        verify(conversationArchivePort).findOrCreateConversation("app-a", "user-1", "session-a");
        verify(conversationArchivePort).findOrCreateConversation("app-b", "user-1", "session-b");
    }

    @Test
    void deleteSession_delegatesExplicitIdentityWithoutLocalCache() {
        chatService.deleteSession("agent-a", "user-1", "session-1");
        chatService.deleteSession("agent-a", "user-1", "session-1");

        // 删除幂等性由实际 SessionStore 保障；application 不维护可过期的会话映射。
        verify(agentRuntimePort, times(2)).deleteSession("agent-a", "user-1", "session-1");
    }
}
