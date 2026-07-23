package com.hh.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.google.adk.events.Event;
import com.google.adk.sessions.Session;
import com.hh.infrastructure.adapter.repository.ConversationArchiveRepository;
import com.hh.infrastructure.agent.session.impl.MySqlSessionStore;
import com.hh.infrastructure.dao.ConversationDao;
import com.hh.infrastructure.dao.MessageDao;
import com.hh.infrastructure.dao.SessionDao;
import com.hh.infrastructure.dao.SessionEventDao;
import com.hh.infrastructure.dao.po.ConversationPO;
import com.hh.infrastructure.dao.po.SessionEventPO;
import com.hh.infrastructure.dao.po.SessionPO;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证持久化适配器把唯一键冲突作为幂等成功处理；不启动 Spring Context，也不访问真实数据库。
 */
class IdempotentPersistenceAdapterTest {

    @Test
    void mapperIdempotentWritesUseMySqlUpsert() throws Exception {
        assertXmlUpsert(ConversationDao.class, "upsertByIdentity", "mapper/ConversationDao.xml");
        assertXmlUpsert(SessionDao.class, "upsertByIdentity", "mapper/SessionDao.xml");
        assertXmlUpsert(SessionEventDao.class, "upsertByEventIdentity", "mapper/SessionEventDao.xml");
    }

    @Test
    void duplicateConversationCreationReadsTheDatabaseWinnerInsteadOfUsingFindThenInsert() {
        ConversationDao conversationDao = mock(ConversationDao.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationArchiveRepository repository = new ConversationArchiveRepository(conversationDao, messageDao);
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
        MySqlSessionStore store = new MySqlSessionStore(sessionDao, sessionEventDao);
        SessionPO persisted = SessionPO.builder()
                .id(7L)
                .appName("app-a")
                .userId("user-a")
                .sessionId("session-a")
                .stateJson("{}")
                .build();
        when(sessionDao.selectOne(org.mockito.ArgumentMatchers.<Wrapper<SessionPO>>any())).thenReturn(persisted);
        when(sessionEventDao.selectList(org.mockito.ArgumentMatchers.<Wrapper<SessionEventPO>>any()))
                .thenReturn(List.of());

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
        MySqlSessionStore store = new MySqlSessionStore(sessionDao, sessionEventDao);
        Session session = Session.builder("session-a")
                .appName("app-a")
                .userId("user-a")
                .state(new ConcurrentHashMap<>())
                .events(new ArrayList<>())
                .build();
        Event event = Event.builder()
                .id("event-a")
                .author("agent-a")
                .timestamp(1L)
                .build();

        store.appendEvent(session, event).blockingGet();
        store.appendEvent(session, event).blockingGet();

        // 不再为每次重试额外查询 event；MySQL 联合唯一键是唯一的并发裁决点。
        verify(sessionEventDao, times(2)).upsertByEventIdentity(any());
        verify(sessionEventDao, never()).selectOne(org.mockito.ArgumentMatchers.<Wrapper<SessionEventPO>>any());
    }

    /**
     * 解析真实 XML，避免仅因资源文件存在而漏掉 namespace 或 statement id 错配。
     */
    private <T> void assertXmlUpsert(Class<T> mapperType, String methodName, String resource) throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(mapperType);
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments(), mapperType.getName())
                    .parse();
        }

        String statementId = mapperType.getName() + "." + methodName;
        assertThat(configuration.hasStatement(statementId)).isTrue();
        String sql = configuration.getMappedStatement(statementId).getBoundSql(null).getSql();
        assertThat(sql.replaceAll("\\s+", " "))
                .contains("ON DUPLICATE KEY UPDATE id = id")
                .doesNotContain("INSERT IGNORE");
    }
}
