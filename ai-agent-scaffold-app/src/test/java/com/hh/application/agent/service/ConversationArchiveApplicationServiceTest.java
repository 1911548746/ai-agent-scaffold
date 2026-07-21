package com.hh.application.agent.service;

import com.hh.domain.agent.adapter.repository.IConversationArchiveRepository;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通过内存 fake 验证 application 用例，不加载 Spring Context，也不会访问数据库。
 */
class ConversationArchiveApplicationServiceTest {

    @Test
    void archiveUseCaseReadsAndDeletesConversationThroughPort() {
        InMemoryConversationArchiveRepository repository = new InMemoryConversationArchiveRepository();
        Long conversationId = repository.findOrCreateConversation("app-a", "user-1", "session-1");
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(repository);

        assertThat(useCase.listConversations("user-1"))
                .extracting(ConversationVO::getId)
                .containsExactly(conversationId);
        assertThat(useCase.getConversation(conversationId).getSessionId()).isEqualTo("session-1");

        useCase.deleteConversation(conversationId);
        assertThat(useCase.listConversations("user-1")).isEmpty();
    }

    @Test
    void missingConversationKeepsExistingStableErrorCode() {
        ConversationArchiveApplicationService useCase = new ConversationArchiveApplicationService(
                new InMemoryConversationArchiveRepository());

        assertThatThrownBy(() -> useCase.getConversation(404L))
                .isInstanceOfSatisfying(AppException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ResponseCode.AGENT_NOT_FOUND.getCode()));
    }

    /**
     * 用于验证用例和端口协作的最小内存实现，不模拟 MyBatis 或数据库行为。
     */
    private static final class InMemoryConversationArchiveRepository implements IConversationArchiveRepository {

        private final AtomicLong sequence = new AtomicLong();
        private final List<ConversationVO> conversations = new ArrayList<>();

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
        public ConversationVO getConversation(Long conversationId) {
            return conversations.stream()
                    .filter(conversation -> conversation.getId().equals(conversationId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<MessageVO> listMessages(Long conversationId) {
            return List.of();
        }

        @Override
        public void deleteConversation(Long conversationId) {
            conversations.removeIf(conversation -> conversation.getId().equals(conversationId));
        }
    }
}
