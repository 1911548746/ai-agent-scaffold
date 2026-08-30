package com.hh.application.agent.service;

import com.hh.application.agent.model.ConversationDeletionCommand;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.SessionOperationLockPort;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通过内存 fake 验证 application 用例，不加载 Spring Context，也不会访问数据库。
 */
class ConversationArchiveApplicationServiceTest {

    @Test
    void deleteConversation_validatesIdentityAndDelegatesAtomicDeletion() {
        InMemoryConversationArchiveRepository repository = new InMemoryConversationArchiveRepository();
        Long conversationId = repository.findOrCreateConversation("app-a", "user-1", "session-1");
        AgentRuntimePort agentRuntimePort = mock(AgentRuntimePort.class);
        SessionOperationLockPort lockPort = mock(SessionOperationLockPort.class);
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(lockPort.acquire("agent-a", "user-1", "session-1"))
                .thenReturn(() -> { });
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(
                repository, agentRuntimePort, mock(AttachmentStoragePort.class), lockPort);

        assertThat(useCase.listConversations("user-1"))
                .extracting(ConversationVO::getId)
                .containsExactly(conversationId);
        String publicConversationId = repository.conversationIdFor(conversationId);
        assertThat(useCase.getConversation(publicConversationId, "user-1").getSessionId())
                .isEqualTo("session-1");

        useCase.deleteConversation(new ConversationDeletionCommand(
                publicConversationId, "agent-a", "user-1", "session-1"));

        assertThat(repository.deletionRequests)
                .containsExactly(new DeletionRequest(conversationId, "app-a", "user-1", "session-1"));
    }

    @Test
    void deleteConversationCleansSessionAttachmentsAfterArchiveDeletion() {
        InMemoryConversationArchiveRepository repository = new InMemoryConversationArchiveRepository();
        Long conversationId = repository.findOrCreateConversation("app-a", "user-1", "session-1");
        AgentRuntimePort agentRuntimePort = mock(AgentRuntimePort.class);
        AttachmentStoragePort attachmentStoragePort = mock(AttachmentStoragePort.class);
        SessionOperationLockPort lockPort = mock(SessionOperationLockPort.class);
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(lockPort.acquire("agent-a", "user-1", "session-1"))
                .thenReturn(() -> { });
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(
                repository, agentRuntimePort, attachmentStoragePort, lockPort);
        String publicConversationId = repository.conversationIdFor(conversationId);

        useCase.deleteConversation(new ConversationDeletionCommand(
                publicConversationId, "agent-a", "user-1", "session-1"));

        verify(attachmentStoragePort).deleteSession("app-a", "user-1", "session-1");
    }

    @Test
    void missingConversationKeepsExistingStableErrorCode() {
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(
                new InMemoryConversationArchiveRepository(),
                mock(AgentRuntimePort.class),
                mock(AttachmentStoragePort.class),
                mock(SessionOperationLockPort.class));

        assertThatThrownBy(() -> useCase.getConversation(
                "6f1a1d0e-0d7d-4e8f-95b8-2f5c1e6b0e42", "user-1"))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ResponseCode.AGENT_NOT_FOUND.getCode()));
    }

    @Test
    void deleteConversation_repeatedMissingArchiveStillDelegatesForIdempotentCleanup() {
        AgentRuntimePort agentRuntimePort = mock(AgentRuntimePort.class);
        SessionOperationLockPort lockPort = mock(SessionOperationLockPort.class);
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        when(lockPort.acquire("agent-a", "user-1", "session-1"))
                .thenReturn(() -> { });
        InMemoryConversationArchiveRepository repository = new InMemoryConversationArchiveRepository();
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(
                repository, agentRuntimePort, mock(AttachmentStoragePort.class), lockPort);
        ConversationDeletionCommand command = new ConversationDeletionCommand(
                "6f1a1d0e-0d7d-4e8f-95b8-2f5c1e6b0e42", "agent-a", "user-1", "session-1");

        useCase.deleteConversation(command);
        useCase.deleteConversation(command);

        // 归档已不存在时仍按完整 Session 身份清理，客户端重试不会因为“零行删除”失败。
        assertThat(repository.deletionRequests)
                .containsExactly(
                        new DeletionRequest(null, "app-a", "user-1", "session-1"),
                        new DeletionRequest(null, "app-a", "user-1", "session-1"));
    }

    @Test
    void deleteConversation_rejectsMismatchedSessionIdentity() {
        InMemoryConversationArchiveRepository repository = new InMemoryConversationArchiveRepository();
        Long conversationId = repository.findOrCreateConversation("app-a", "user-1", "session-1");
        AgentRuntimePort agentRuntimePort = mock(AgentRuntimePort.class);
        when(agentRuntimePort.appName("agent-a")).thenReturn("app-a");
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(
                repository,
                agentRuntimePort,
                mock(AttachmentStoragePort.class),
                mock(SessionOperationLockPort.class));
        String publicConversationId = repository.conversationIdFor(conversationId);

        assertThatThrownBy(() -> useCase.deleteConversation(new ConversationDeletionCommand(
                publicConversationId, "agent-a", "user-1", "another-session")))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ResponseCode.ILLEGAL_PARAMETER.getCode()));

        assertThat(repository.deletionRequests).isEmpty();
    }

    /**
     * 用于验证用例和端口协作的最小内存实现，不模拟 MyBatis 或数据库行为。
     */
    private static final class InMemoryConversationArchiveRepository implements ConversationArchivePort {

        private final AtomicLong sequence = new AtomicLong();
        private final List<ConversationVO> conversations = new ArrayList<>();
        private final List<DeletionRequest> deletionRequests = new ArrayList<>();

        @Override
        public Long findOrCreateConversation(String appName, String userId, String sessionId) {
            return conversations.stream()
                    .filter(conversation -> conversation.getAppName().equals(appName)
                            && conversation.getUserId().equals(userId)
                            && conversation.getSessionId().equals(sessionId))
                    .findFirst()
                    .map(ConversationVO::getId)
                    .orElseGet(() -> {
                        long id = sequence.incrementAndGet();
                        conversations.add(ConversationVO.builder()
                                .id(id)
                                .conversationId(UUID.nameUUIDFromBytes((appName + userId + sessionId).getBytes()).toString())
                                .appName(appName)
                                .userId(userId)
                                .sessionId(sessionId)
                                .build());
                        return id;
                    });
        }

        @Override
        public List<ConversationVO> listConversations(String userId) {
            return conversations.stream()
                    .filter(conversation -> conversation.getUserId().equals(userId))
                    .toList();
        }

        @Override
        public ConversationVO getConversationByBusinessId(String conversationId) {
            return conversations.stream()
                    .filter(conversation -> conversation.getConversationId().equals(conversationId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<MessageVO> listMessages(Long conversationId) {
            return List.of();
        }

        private String conversationIdFor(Long physicalId) {
            return conversations.stream()
                    .filter(conversation -> conversation.getId().equals(physicalId))
                    .map(ConversationVO::getConversationId)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void deleteConversationSession(Long conversationId, String appName, String userId, String sessionId) {
            deletionRequests.add(new DeletionRequest(conversationId, appName, userId, sessionId));
            conversations.removeIf(conversation -> conversation.getId().equals(conversationId)
                    && conversation.getAppName().equals(appName)
                    && conversation.getUserId().equals(userId)
                    && conversation.getSessionId().equals(sessionId));
        }

    }

    private record DeletionRequest(Long conversationId, String appName, String userId, String sessionId) {
    }
}
