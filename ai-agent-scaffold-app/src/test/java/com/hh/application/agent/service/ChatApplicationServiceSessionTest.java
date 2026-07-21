package com.hh.application.agent.service;

import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.Session;
import com.hh.domain.agent.adapter.repository.IConversationArchiveRepository;
import com.hh.domain.agent.adapter.repository.IMessageArchiveRepository;
import com.hh.domain.agent.model.runtime.AgentRuntime;
import com.hh.domain.agent.model.runtime.AgentRuntimeRegistry;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话创建只验证本地编排逻辑，所有 ADK 依赖均用 Mock 替代，避免测试连接模型或数据库。
 */
@ExtendWith(MockitoExtension.class)
class ChatApplicationServiceSessionTest {

    @Mock
    private AgentRuntimeRegistry runtimeRegistry;
    @Mock
    private IConversationArchiveRepository conversationArchiveRepository;
    @Mock
    private IMessageArchiveRepository messageArchiveRepository;

    @InjectMocks
    private ChatApplicationService chatService;

    @Test
    void createSession_sameUserAndAgent_createsIndependentSessions() {
        BaseSessionService sessionService = mock(BaseSessionService.class);
        Runner runner = mock(Runner.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(runtimeRegistry.find("agent-a"))
                .thenReturn(Optional.of(runtime("app-a", "agent-a", runner)));

        AtomicInteger sequence = new AtomicInteger();
        when(sessionService.createSession("app-a", "user-1"))
                .thenAnswer(invocation -> Single.just(session("session-" + sequence.incrementAndGet(), "app-a", "user-1")));

        String firstSessionId = chatService.createSession("agent-a", "user-1");
        String secondSessionId = chatService.createSession("agent-a", "user-1");

        // 每次显式创建都必须交给 ADK 创建新会话，不能由 userId 缓存短路。
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);
        verify(sessionService, times(2)).createSession("app-a", "user-1");
        verify(conversationArchiveRepository).findOrCreateConversation("app-a", "user-1", firstSessionId);
        verify(conversationArchiveRepository).findOrCreateConversation("app-a", "user-1", secondSessionId);
    }

    @Test
    void createSession_sameUserAcrossAgents_usesEachAgentsAppScope() {
        BaseSessionService firstSessionService = mock(BaseSessionService.class);
        BaseSessionService secondSessionService = mock(BaseSessionService.class);
        Runner firstRunner = mock(Runner.class);
        Runner secondRunner = mock(Runner.class);
        when(firstRunner.sessionService()).thenReturn(firstSessionService);
        when(secondRunner.sessionService()).thenReturn(secondSessionService);
        when(runtimeRegistry.find("agent-a"))
                .thenReturn(Optional.of(runtime("app-a", "agent-a", firstRunner)));
        when(runtimeRegistry.find("agent-b"))
                .thenReturn(Optional.of(runtime("app-b", "agent-b", secondRunner)));
        when(firstSessionService.createSession("app-a", "user-1"))
                .thenReturn(Single.just(session("session-a", "app-a", "user-1")));
        when(secondSessionService.createSession("app-b", "user-1"))
                .thenReturn(Single.just(session("session-b", "app-b", "user-1")));

        String firstSessionId = chatService.createSession("agent-a", "user-1");
        String secondSessionId = chatService.createSession("agent-b", "user-1");

        assertThat(firstSessionId).isEqualTo("session-a");
        assertThat(secondSessionId).isEqualTo("session-b");
        verify(firstSessionService).createSession("app-a", "user-1");
        verify(secondSessionService).createSession("app-b", "user-1");
    }

    @Test
    void deleteSession_thenCreateSession_createsNewSessionAndDeletionRemainsIdempotent() {
        BaseSessionService sessionService = mock(BaseSessionService.class);
        Runner runner = mock(Runner.class);
        when(runner.sessionService()).thenReturn(sessionService);
        when(runtimeRegistry.find("agent-a"))
                .thenReturn(Optional.of(runtime("app-a", "agent-a", runner)));
        when(sessionService.createSession("app-a", "user-1"))
                .thenReturn(Single.just(session("old-session", "app-a", "user-1")))
                .thenReturn(Single.just(session("new-session", "app-a", "user-1")));
        when(sessionService.deleteSession("app-a", "user-1", "old-session"))
                .thenReturn(Completable.complete());

        String deletedSessionId = chatService.createSession("agent-a", "user-1");
        chatService.deleteSession("agent-a", "user-1", deletedSessionId);
        chatService.deleteSession("agent-a", "user-1", deletedSessionId);
        String recreatedSessionId = chatService.createSession("agent-a", "user-1");

        // 删除后重新创建必须再次访问 ADK，而不是从内存映射返回已删除的 ID。
        assertThat(recreatedSessionId).isEqualTo("new-session");
        verify(sessionService, times(2)).createSession("app-a", "user-1");
        verify(sessionService, times(2)).deleteSession("app-a", "user-1", "old-session");
    }

    private AgentRuntime runtime(String appName, String agentId, Runner runner) {
        return new AgentRuntime(appName, agentId, agentId, agentId, runner);
    }

    private Session session(String sessionId, String appName, String userId) {
        return Session.builder(sessionId)
                .appName(appName)
                .userId(userId)
                .build();
    }
}
