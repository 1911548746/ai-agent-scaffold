package com.hh.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.events.EventCompaction;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.Session;
import com.google.genai.types.CustomMetadata;
import com.google.genai.types.StringList;
import com.hh.infrastructure.agent.session.compaction.cleanup.SessionEventCompactionCleanupSettings;
import com.hh.infrastructure.agent.session.compaction.cleanup.CompactionCleanupContext;
import com.hh.infrastructure.agent.session.compaction.cleanup.policy.impl.FixedSizeCompactionCleanupPolicy;
import com.hh.infrastructure.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import com.hh.infrastructure.agent.session.impl.MySqlSessionStore;
import com.hh.infrastructure.dao.SessionDao;
import com.hh.infrastructure.dao.SessionEventDao;
import com.hh.infrastructure.dao.po.SessionEventCompactionCleanup;
import com.hh.infrastructure.dao.po.SessionEventPO;
import com.hh.infrastructure.dao.po.SessionEventQuery;
import com.hh.infrastructure.dao.po.SessionPO;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 DATA-03 的回放窗口与压缩后精确清理，不连接真实 MySQL 或模型服务。
 */
class SessionEventRetentionTest {

    @Test
    void defaultLoadKeepsOverlapEventsBeforeCompactionSummary() {
        SessionEventDao eventDao = mock(SessionEventDao.class);
        when(eventDao.selectSessionEvents(any(SessionEventQuery.class)))
                .thenReturn(List.of(eventRow("overlap-a"), eventRow("compaction-a"), eventRow("recent-a")));
        MySqlSessionStore store = store(mock(SessionDao.class), eventDao, true);

        List<Event> events = store.listEvents("app-a", "user-a", "session-a").blockingGet().events();

        assertThat(events).extracting(Event::id).containsExactly("overlap-a", "compaction-a", "recent-a");
        ArgumentCaptor<SessionEventQuery> queryCaptor = ArgumentCaptor.forClass(SessionEventQuery.class);
        verify(eventDao).selectSessionEvents(queryCaptor.capture());
        assertThat(queryCaptor.getValue())
                .extracting(SessionEventQuery::getAppName, SessionEventQuery::getUserId, SessionEventQuery::getSessionId)
                .containsExactly("app-a", "user-a", "session-a");
        verify(eventDao, never()).selectRecentEvents(any());
        verify(eventDao, never()).selectEventsAfterTimestamp(any());
    }

    @Test
    void explicitAdkRetentionAndTimestampUseDatabaseFiltersBeforeJsonDeserialization() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao eventDao = mock(SessionEventDao.class);
        when(sessionDao.selectOne(org.mockito.ArgumentMatchers.<Wrapper<SessionPO>>any()))
                .thenReturn(SessionPO.builder()
                        .appName("app-a")
                        .userId("user-a")
                        .sessionId("session-a")
                        .stateJson("{}")
                        .build());
        when(eventDao.selectRecentEvents(any(SessionEventQuery.class))).thenReturn(List.of(eventRow("recent-a")));
        when(eventDao.selectEventsAfterTimestamp(any(SessionEventQuery.class))).thenReturn(List.of(eventRow("after-a")));
        MySqlSessionStore store = store(sessionDao, eventDao, true);

        Session recent = store.getSession("app-a", "user-a", "session-a",
                Optional.of(GetSessionConfig.builder().numRecentEvents(3).build())).blockingGet();
        Session after = store.getSession("app-a", "user-a", "session-a",
                Optional.of(GetSessionConfig.builder().afterTimestamp(Instant.ofEpochSecond(99)).build())).blockingGet();

        assertThat(recent.events()).extracting(Event::id).containsExactly("recent-a");
        assertThat(after.events()).extracting(Event::id).containsExactly("after-a");
        ArgumentCaptor<SessionEventQuery> recentQuery = ArgumentCaptor.forClass(SessionEventQuery.class);
        verify(eventDao).selectRecentEvents(recentQuery.capture());
        assertThat(recentQuery.getValue().getLimit()).isEqualTo(3);
        ArgumentCaptor<SessionEventQuery> afterQuery = ArgumentCaptor.forClass(SessionEventQuery.class);
        verify(eventDao).selectEventsAfterTimestamp(afterQuery.capture());
        assertThat(afterQuery.getValue().getAfterTimestamp()).isEqualTo(99L);
    }

    @Test
    void trackingSummarizerAddsExactSourceEventIdsToSummaryMetadata() {
        TrackingLlmEventSummarizer summarizer = new TrackingLlmEventSummarizer(
                events -> Maybe.just(Event.builder().id("summary-a").build()),
                new FixedSizeCompactionCleanupPolicy(),
                new CompactionCleanupContext(null, 30));

        Event summary = summarizer.summarizeEvents(List.of(
                Event.builder().id("source-a").build(),
                Event.builder().id("source-b").build(),
                Event.builder().id("source-a").build()))
                .blockingGet();

        assertThat(summary.customMetadata()).isPresent();
        List<String> eventIds = summary.customMetadata().orElseThrow().stream()
                .filter(item -> TrackingLlmEventSummarizer.DELETABLE_EVENT_IDS_METADATA_KEY
                        .equals(item.key().orElse(null)))
                .flatMap(item -> item.stringListValue().stream())
                .flatMap(value -> value.values().stream())
                .flatMap(List::stream)
                .toList();
        assertThat(eventIds).containsExactly("source-a", "source-b");
    }

    @Test
    void compactionEventWritesSummaryThenDeletesOnlyTrackedSourceEventsInOneTransaction() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao eventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, eventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));

        store.appendEvent(session("session-a"), compactionEvent("summary-a", "source-a", "source-b")).blockingGet();

        ArgumentCaptor<SessionEventCompactionCleanup> cleanupCaptor =
                ArgumentCaptor.forClass(SessionEventCompactionCleanup.class);
        verify(eventDao).deleteCompactedEvents(cleanupCaptor.capture());
        assertThat(cleanupCaptor.getValue().getEventIds()).containsExactly("source-a", "source-b");
        assertThat(cleanupCaptor.getValue().getSummaryEventId()).isEqualTo("summary-a");

        InOrder inOrder = inOrder(transactionManager, eventDao, sessionDao);
        inOrder.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        inOrder.verify(eventDao).upsertByEventIdentity(any(SessionEventPO.class));
        inOrder.verify(sessionDao).updateStateByIdentity(any(SessionPO.class));
        inOrder.verify(eventDao).deleteCompactedEvents(any(SessionEventCompactionCleanup.class));
        inOrder.verify(transactionManager).commit(any(TransactionStatus.class));
    }

    @Test
    void disabledCleanupKeepsTrackedSourceEventsForDevelopmentDebugging() {
        SessionEventDao eventDao = mock(SessionEventDao.class);
        MySqlSessionStore store = store(mock(SessionDao.class), eventDao, false);

        store.appendEvent(session("session-a"), compactionEvent("summary-a", "source-a")).blockingGet();

        verify(eventDao, never()).deleteCompactedEvents(any(SessionEventCompactionCleanup.class));
    }

    @Test
    void legacyCompactionWithoutTrackedMetadataDoesNotDeleteEvents() {
        SessionEventDao eventDao = mock(SessionEventDao.class);
        MySqlSessionStore store = store(mock(SessionDao.class), eventDao, true);
        Event legacyCompaction = Event.builder()
                .id("legacy-summary-a")
                .timestamp(100L)
                .actions(EventActions.builder()
                        .compaction(EventCompaction.builder()
                                .startTimestamp(1L)
                                .endTimestamp(99L)
                                .compactedContent(com.google.genai.types.Content.builder().build())
                                .build())
                        .build())
                .build();

        store.appendEvent(session("session-a"), legacyCompaction).blockingGet();

        // 历史摘要没有精确 ID 时不能退化为时间范围删除，必须保守保留原始 Event。
        verify(eventDao, never()).deleteCompactedEvents(any(SessionEventCompactionCleanup.class));
    }

    @Test
    void cleanupFailureRollsBackSummaryAndSessionSnapshotTogether() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionEventDao eventDao = mock(SessionEventDao.class);
        PlatformTransactionManager transactionManager = transactionManager();
        when(eventDao.deleteCompactedEvents(any(SessionEventCompactionCleanup.class)))
                .thenThrow(new IllegalStateException("compaction cleanup failed"));
        MySqlSessionStore store = new MySqlSessionStore(
                sessionDao, eventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));

        assertThatThrownBy(() -> store.appendEvent(
                session("session-a"), compactionEvent("summary-a", "source-a")).blockingGet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("compaction cleanup failed");

        // 摘要已经尝试写入但清理失败时，TransactionTemplate 必须回滚整个事务，不能留下孤立摘要。
        verify(transactionManager).rollback(any(TransactionStatus.class));
    }

    private MySqlSessionStore store(SessionDao sessionDao, SessionEventDao eventDao, boolean deleteCompactedEvents) {
        return new MySqlSessionStore(
                sessionDao,
                eventDao,
                transactionManager(),
                new SessionEventCompactionCleanupSettings(deleteCompactedEvents));
    }

    private PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return transactionManager;
    }

    private Session session(String sessionId) {
        return Session.builder(sessionId)
                .appName("app-a")
                .userId("user-a")
                .state(new java.util.concurrent.ConcurrentHashMap<>())
                .events(new java.util.ArrayList<>())
                .build();
    }

    private Event compactionEvent(String summaryEventId, String... sourceEventIds) {
        return Event.builder()
                .id(summaryEventId)
                .author("agent-a")
                .timestamp(100L)
                .actions(EventActions.builder()
                        .compaction(EventCompaction.builder()
                                .startTimestamp(1L)
                                .endTimestamp(99L)
                                .compactedContent(com.google.genai.types.Content.builder().build())
                                .build())
                        .build())
                .customMetadata(List.of(CustomMetadata.builder()
                        .key(TrackingLlmEventSummarizer.DELETABLE_EVENT_IDS_METADATA_KEY)
                        .stringListValue(StringList.builder().values(sourceEventIds).build())
                        .build()))
                .build();
    }

    private SessionEventPO eventRow(String eventId) {
        Event event = Event.builder()
                .id(eventId)
                .author("agent-a")
                .timestamp(100L)
                .build();
        return SessionEventPO.builder().eventJson(event.toJson()).build();
    }
}
