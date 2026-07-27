package com.hh.infrastructure.persistence;

import com.hh.infrastructure.agent.adapter.repository.ConversationArchiveRepository;
import com.hh.infrastructure.agent.dao.ConversationDao;
import com.hh.infrastructure.agent.dao.MessageDao;
import com.hh.infrastructure.agent.dao.SessionDao;
import com.hh.infrastructure.agent.dao.SessionEventDao;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 固化 DATA-04 的删除顺序及事务声明；测试不启动 Spring Context，也不连接真实 MySQL。
 */
class ConversationArchiveRepositoryTest {

    @Test
    void deletion_declaresRollbackTransactionAndRemovesAllSessionDataInOrder() throws Exception {
        ConversationDao conversationDao = mock(ConversationDao.class);
        MessageDao messageDao = mock(MessageDao.class);
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        ConversationArchiveRepository repository = new ConversationArchiveRepository(
                conversationDao, messageDao, sessionDao, sessionEventDao);

        Transactional transactional = ConversationArchiveRepository.class
                .getMethod("deleteConversationSession", Long.class, String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(Arrays.asList(transactional.rollbackFor())).contains(Exception.class);

        repository.deleteConversationSession(10L, "app-a", "user-1", "session-1");

        // 删除顺序同时兼容当前无外键表结构和未来补充外键后的约束关系。
        var order = inOrder(messageDao, conversationDao, sessionEventDao, sessionDao);
        order.verify(messageDao).delete(any());
        order.verify(conversationDao).delete(any());
        order.verify(sessionEventDao).delete(any());
        order.verify(sessionDao).delete(any());
    }

    @Test
    void deletion_stopsBeforeRemovingOtherTablesWhenMessageRemovalFails() {
        ConversationDao conversationDao = mock(ConversationDao.class);
        MessageDao messageDao = mock(MessageDao.class);
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        when(messageDao.delete(any())).thenThrow(new IllegalStateException("message delete failed"));
        ConversationArchiveRepository repository = new ConversationArchiveRepository(
                conversationDao, messageDao, sessionDao, sessionEventDao);

        assertThatThrownBy(() -> repository.deleteConversationSession(10L, "app-a", "user-1", "session-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("message delete failed");

        // 线上经 Spring 代理执行时，该异常将触发 rollbackFor = Exception.class 的整体回滚。
        org.mockito.Mockito.verify(conversationDao, never()).delete(any());
        org.mockito.Mockito.verify(sessionEventDao, never()).delete(any());
        org.mockito.Mockito.verify(sessionDao, never()).delete(any());
    }
}
