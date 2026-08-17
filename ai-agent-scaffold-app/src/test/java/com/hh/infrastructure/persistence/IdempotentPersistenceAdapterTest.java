package com.hh.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.Session;
import com.hh.infrastructure.agent.adapter.repository.ConversationArchiveRepository;
import com.hh.infrastructure.agent.agent.session.impl.MySqlSessionStore;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.SessionEventCompactionCleanupSettings;
import com.hh.infrastructure.agent.dao.ConversationDao;
import com.hh.infrastructure.agent.dao.MessageDao;
import com.hh.infrastructure.agent.dao.SessionDao;
import com.hh.infrastructure.agent.dao.SessionEventDao;
import com.hh.infrastructure.agent.dao.po.ConversationPO;
import com.hh.infrastructure.agent.dao.po.SessionEventPO;
import com.hh.infrastructure.agent.dao.po.SessionEventCompactionCleanup;
import com.hh.infrastructure.agent.dao.po.SessionEventIdCleanup;
import com.hh.infrastructure.agent.dao.po.SessionEventQuery;
import com.hh.infrastructure.agent.dao.po.SessionInvocationEventCleanup;
import com.hh.infrastructure.agent.dao.po.SessionPO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

/**
 * 验证持久化适配器把唯一键冲突作为幂等成功处理；不启动 Spring Context，也不访问真实数据库。
 */
class IdempotentPersistenceAdapterTest {

    @Test
    void mapperIdempotentWritesUseMySqlUpsert() throws Exception {
        assertXmlUpsert(ConversationDao.class, "upsertByIdentity", "mapper/ConversationDao.xml");
        assertXmlUpsert(SessionDao.class, "upsertByIdentity", "mapper/SessionDao.xml");
        assertXmlUpsert(SessionEventDao.class, "upsertByEventIdentity", "mapper/SessionEventDao.xml");
        assertXmlStatement(SessionDao.class, "updateStateByIdentity", "mapper/SessionDao.xml",
                "UPDATE sessions", "WHERE app_name = ?", "AND user_id = ?", "AND session_id = ?");
    }

    @Test
    void duplicateConversationCreationReadsTheDatabaseWinnerInsteadOfUsingFindThenInsert() {
        ConversationDao conversationDao = mock(ConversationDao.class);
        MessageDao messageDao = mock(MessageDao.class);
        // DATA-04 将统一删除能力合并进该 Repository；幂等创建测试不触发 Session DAO。
        ConversationArchiveRepository repository = new ConversationArchiveRepository(
                conversationDao, messageDao, mock(SessionDao.class), mock(SessionEventDao.class));
        ConversationPO persisted = ConversationPO.builder().id(42L).build();
        when(conversationDao.selectOne(org.mockito.ArgumentMatchers.<Wrapper<ConversationPO>>any()))
                .thenReturn(persisted);

        // 模拟两个竞争请求：无论其中哪一个先写入，二者都必须得到同一条数据库记录。
        Long firstId = repository.findOrCreateConversation("app-a", "user-a", "session-a");
        Long secondId = repository.findOrCreateConversation("app-a", "user-a", "session-a");

        assertThat(firstId).isEqualTo(42L);
        assertThat(secondId).isEqualTo(42L);
        verify(conversationDao, times(2)).upsertByIdentity(any(ConversationPO.class));
        verify(conversationDao, times(2)).selectOne(org.mockito.ArgumentMatchers.<Wrapper<ConversationPO>>any());
        verify(conversationDao, never()).insert(any(ConversationPO.class));
    }

    @Test
    void duplicateSessionCreationReadsTheDatabaseWinnerInsteadOfThrowingDuplicateKey() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, sessionEventDao, transactionManager(), new SessionEventCompactionCleanupSettings(true));
        SessionPO persisted = SessionPO.builder()
                .id(7L)
                .appName("app-a")
                .userId("user-a")
                .sessionId("session-a")
                .stateJson("{}")
                .build();
        when(sessionDao.selectOne(org.mockito.ArgumentMatchers.<Wrapper<SessionPO>>any())).thenReturn(persisted);
        when(sessionEventDao.selectSessionEvents(any(SessionEventQuery.class))).thenReturn(List.of());

        Session first = store.createSession("app-a", "user-a", new ConcurrentHashMap<>(), "session-a").blockingGet();
        Session second = store.createSession("app-a", "user-a", new ConcurrentHashMap<>(), "session-a").blockingGet();

        assertThat(first.id()).isEqualTo("session-a");
        assertThat(second.id()).isEqualTo("session-a");
        verify(sessionDao, times(2)).upsertByIdentity(any(SessionPO.class));
        verify(sessionDao, never()).insert(any(SessionPO.class));
    }

    @Test
    void duplicateEventDeliveryDelegatesConflictHandlingToUniqueKey() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, sessionEventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));
        Session session = session("session-a");
        Event event = Event.builder()
                .id("event-a")
                .author("agent-a")
                .timestamp(1L)
                .build();

        store.appendEvent(session, event).blockingGet();
        store.appendEvent(session, event).blockingGet();

        // 不再为每次重试额外查询 event；MySQL 联合唯一键是唯一的并发裁决点。
        verify(sessionEventDao, times(2)).upsertByEventIdentity(any());
        verify(sessionDao, times(2)).updateStateByIdentity(any(SessionPO.class));
        verify(sessionEventDao, never()).selectOne(org.mockito.ArgumentMatchers.<Wrapper<SessionEventPO>>any());
        verify(sessionDao, never()).selectOne(org.mockito.ArgumentMatchers.<Wrapper<SessionPO>>any());
        verify(sessionDao, never()).updateById(any(SessionPO.class));

        // 两次写入都必须在订阅线程开启的事务内按“Event -> Session”顺序提交。
        InOrder inOrder = inOrder(transactionManager, sessionEventDao, sessionDao);
        for (int index = 0; index < 2; index++) {
            inOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
            inOrder.verify(sessionEventDao).upsertByEventIdentity(any(SessionEventPO.class));
            inOrder.verify(sessionDao).updateStateByIdentity(any(SessionPO.class));
            inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
        }
    }

    @Test
    void partialEventWithoutStateDeltaDoesNotWriteDatabase() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, sessionEventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));
        Event partialEvent = Event.builder()
                .id("partial-a")
                .author("agent-a")
                .timestamp(1L)
                .partial(true)
                .build();

        Session session = session("session-a");
        store.appendEvent(session, partialEvent).blockingGet();

        // token 片段只供当前请求流消费，不能触发 event 或 session 的数据库往返。
        assertThat(session.events()).isEmpty();
        verifyNoInteractions(sessionDao, sessionEventDao, transactionManager);
    }

    @Test
    void partialEventWithStateDeltaUpdatesSessionWithoutPersistingEvent() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, sessionEventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));
        Session session = session("session-a");
        Event partialEvent = Event.builder()
                .id("partial-state-a")
                .author("agent-a")
                .timestamp(1L)
                .partial(true)
                .actions(EventActions.builder().stateDelta(Map.of("locale", "zh-CN")).build())
                .build();

        store.appendEvent(session, partialEvent).blockingGet();

        assertThat(session.state()).containsEntry("locale", "zh-CN");
        assertThat(session.events()).isEmpty();
        ArgumentCaptor<SessionPO> snapshotCaptor = ArgumentCaptor.forClass(SessionPO.class);
        verify(sessionDao).updateStateByIdentity(snapshotCaptor.capture());
        assertThat(snapshotCaptor.getValue().getStateJson()).contains("locale");
        verifyNoInteractions(sessionEventDao);

        InOrder inOrder = inOrder(transactionManager, sessionDao);
        inOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        inOrder.verify(sessionDao).updateStateByIdentity(any(SessionPO.class));
        inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
    }

    @Test
    void streamingChunksStayTransientAndOnlyCompletedResponseIsPersisted() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, sessionEventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));
        Session session = session("session-a");

        store.appendEvent(session, Event.builder()
                .id("partial-a")
                .author("agent-a")
                .timestamp(1L)
                .partial(true)
                .build()).blockingGet();
        store.appendEvent(session, Event.builder()
                .id("partial-b")
                .author("agent-a")
                .timestamp(2L)
                .partial(true)
                .build()).blockingGet();
        Event completed = Event.builder()
                .id("completed-a")
                .author("agent-a")
                .timestamp(3L)
                .partial(false)
                .build();
        store.appendEvent(session, completed).blockingGet();

        verify(sessionEventDao).upsertByEventIdentity(any(SessionEventPO.class));
        verify(sessionDao).updateStateByIdentity(any(SessionPO.class));
        assertThat(session.events()).containsExactly(completed);
    }

    @Test
    void eventWriteFailureRollsBackAndDoesNotUpdateSessionSnapshot() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao sessionEventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, sessionEventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));
        when(sessionEventDao.upsertByEventIdentity(any(SessionEventPO.class)))
                .thenThrow(new IllegalStateException("event write failed"));
        Event event = Event.builder()
                .id("event-failed-a")
                .author("agent-a")
                .timestamp(1L)
                .build();

        assertThatThrownBy(() -> store.appendEvent(session("session-a"), event).blockingGet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("event write failed");

        // Event 写入失败时 TransactionTemplate 必须回滚，不能继续写入 state 快照造成跨表不一致。
        verify(transactionManager).rollback(any(TransactionStatus.class));
        verify(sessionDao, never()).updateStateByIdentity(any(SessionPO.class));
    }

    @Test
    void mapperCompactionQueriesKeepOverlapEventsAndDeleteOnlyExactEventIds() throws Exception {
        assertXmlStatement(SessionEventDao.class, "selectSessionEvents", "mapper/SessionEventDao.xml",
                "FROM session_events e", "ORDER BY e.id ASC");
        assertXmlStatement(SessionEventDao.class, "selectRecentEvents", "mapper/SessionEventDao.xml",
                "ORDER BY e.id DESC", "LIMIT ?", "ORDER BY recent.id ASC");
        assertXmlStatement(SessionEventDao.class, "selectEventsAfterTimestamp", "mapper/SessionEventDao.xml",
                "e.event_ts > ?", "ORDER BY e.id ASC");
        assertXmlStatement(SessionEventDao.class, "deleteCompactedEvents", "mapper/SessionEventDao.xml",
                "DELETE FROM session_events", "event_id != ?", "event_id IN ( ? )");
        assertXmlStatement(SessionEventDao.class, "deleteInvocationEvents", "mapper/SessionEventDao.xml",
                "DELETE FROM session_events", "WHERE app_name = ?", "AND user_id = ?",
                "AND session_id = ?", "AND invocation_id IN ( ? )");
        assertXmlStatement(SessionEventDao.class, "deleteEventsByIds", "mapper/SessionEventDao.xml",
                "DELETE FROM session_events", "WHERE app_name = ?", "AND user_id = ?",
                "AND session_id = ?", "AND event_id IN ( ? )");
    }

    /**
     * 解析真实 XML，避免仅因资源文件存在而漏掉 namespace 或 statement id 错配。
     */
    private <T> void assertXmlUpsert(Class<T> mapperType, String methodName, String resource) throws Exception {
        String sql = mappedSql(mapperType, methodName, resource);
        assertThat(sql)
                .contains("ON DUPLICATE KEY UPDATE id = id")
                .doesNotContain("INSERT IGNORE");
    }

    private <T> void assertXmlStatement(
            Class<T> mapperType,
            String methodName,
            String resource,
            String... expectedSqlFragments) throws Exception {
        assertThat(mappedSql(mapperType, methodName, resource)).contains(expectedSqlFragments);
    }

    private <T> String mappedSql(Class<T> mapperType, String methodName, String resource) throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(mapperType);
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments(), mapperType.getName())
                    .parse();
        }

        String statementId = mapperType.getName() + "." + methodName;
        assertThat(configuration.hasStatement(statementId)).isTrue();
        return configuration.getMappedStatement(statementId)
                .getBoundSql(mapperParameter(mapperType, methodName))
                .getSql()
                .replaceAll("\\s+", " ");
    }

    /**
     * 含 foreach 的 Mapper 需要真实集合参数才能完成 BoundSql 展开，其余语句仍可使用 null 验证结构。
     */
    private Object mapperParameter(Class<?> mapperType, String methodName) {
        if (mapperType == SessionEventDao.class && "deleteCompactedEvents".equals(methodName)) {
            return SessionEventCompactionCleanup.builder()
                    .appName("app-a")
                    .userId("user-a")
                    .sessionId("session-a")
                    .summaryEventId("summary-a")
                    .eventIds(List.of("source-a"))
                    .build();
        }
        if (mapperType == SessionEventDao.class && "deleteInvocationEvents".equals(methodName)) {
            return SessionInvocationEventCleanup.builder()
                    .appName("app-a")
                    .userId("user-a")
                    .sessionId("session-a")
                    .invocationIds(List.of("invocation-a"))
                    .build();
        }
        if (mapperType == SessionEventDao.class && "deleteEventsByIds".equals(methodName)) {
            return SessionEventIdCleanup.builder()
                    .appName("app-a")
                    .userId("user-a")
                    .sessionId("session-a")
                    .eventIds(List.of("event-a"))
                    .build();
        }
        return null;
    }

    private Session session(String sessionId) {
        return Session.builder(sessionId)
                .appName("app-a")
                .userId("user-a")
                .state(new ConcurrentHashMap<>())
                .events(new ArrayList<>())
                .build();
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return transactionManager;
    }
}
