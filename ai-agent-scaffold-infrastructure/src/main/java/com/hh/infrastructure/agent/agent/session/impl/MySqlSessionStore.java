package com.hh.infrastructure.agent.agent.session.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hh.infrastructure.agent.agent.session.AdkSessionStore;
import com.hh.infrastructure.agent.agent.session.compaction.cleanup.SessionEventCompactionCleanupSettings;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.PureSlidingWindowEventSummarizer;
import com.hh.infrastructure.agent.agent.session.compaction.summarizer.TrackingLlmEventSummarizer;
import com.hh.infrastructure.agent.dao.SessionDao;
import com.hh.infrastructure.agent.dao.SessionEventDao;
import com.hh.infrastructure.agent.support.ADKJsonParseSupport;
import com.hh.infrastructure.agent.dao.po.SessionEventCompactionCleanup;
import com.hh.infrastructure.agent.dao.po.SessionEventIdCleanup;
import com.hh.infrastructure.agent.dao.po.SessionEventPO;
import com.hh.infrastructure.agent.dao.po.SessionEventQuery;
import com.hh.infrastructure.agent.dao.po.SessionInvocationEventCleanup;
import com.hh.infrastructure.agent.dao.po.SessionPO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.adk.events.Event;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 MySQL 的 ADK 会话存储适配器。
 *
 * <p>类名保留 MySQL，明确其基础设施实现角色；ADK 装配代码通过 {@link AdkSessionStore}
 * 访问，避免将数据库技术名称传播到领域定义。</p>
 */
@Service
public class MySqlSessionStore implements AdkSessionStore {

    private final SessionDao sessionDao;
    private final SessionEventDao sessionEventDao;
    private final TransactionTemplate transactionTemplate;
    private final SessionEventCompactionCleanupSettings compactionCleanupSettings;

    @Autowired
    public MySqlSessionStore(
            SessionDao sessionDao,
            SessionEventDao sessionEventDao,
            PlatformTransactionManager transactionManager,
            SessionEventCompactionCleanupSettings compactionCleanupSettings) {
        this.sessionDao = sessionDao;
        this.sessionEventDao = sessionEventDao;
        // RxJava 的 Callable 在订阅时才执行；事务必须在 Callable 内显式开启，不能依赖方法注解。
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.compactionCleanupSettings = compactionCleanupSettings;
    }

    /**
     * 保留给不经过 Spring 组合根的离线测试和历史调用方；生产运行时始终注入显式配置。
     */
    public MySqlSessionStore(
            SessionDao sessionDao,
            SessionEventDao sessionEventDao,
            PlatformTransactionManager transactionManager) {
        this(sessionDao, sessionEventDao, transactionManager, new SessionEventCompactionCleanupSettings(true));
    }

    @Override
    public Single<Session> createSession(
            String appName,
            String userId,
            ConcurrentMap<String, Object> state,
            String sessionId) {
        return Single.fromCallable(() -> {
            String resolvedSessionId = sessionId == null || sessionId.isBlank()
                    ? UUID.randomUUID().toString()
                    : sessionId;

            LocalDateTime now = LocalDateTime.now();
            SessionPO sessionPO = SessionPO.builder()
                    .appName(appName)
                    .userId(userId)
                    .sessionId(resolvedSessionId)
                    .stateJson(ADKJsonParseSupport.toJson(sessionStateSnapshot(state == null ? Map.of() : state)))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            // 联合唯一键负责并发仲裁；重复 createSession 取得已存在的 session，而不是抛重复键异常。
            sessionDao.upsertByIdentity(sessionPO);
            SessionPO persisted = findSession(appName, userId, resolvedSessionId);
            if (persisted == null) {
                throw new IllegalStateException("session idempotent write completed without persisted record");
            }
            return buildSession(persisted, Optional.empty());
        });
    }

    @Override
    public Maybe<Session> getSession(
            String appName,
            String userId,
            String sessionId,
            Optional<GetSessionConfig> configOpt) {
        return Maybe.fromCallable(() -> {
            SessionPO entity = findSession(appName, userId, sessionId);
            if (entity == null) {
                return null;
            }
            return buildSession(entity, configOpt);
        });
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return Single.fromCallable(() -> {
            List<SessionPO> entities = sessionDao.selectList(new LambdaQueryWrapper<SessionPO>()
                    .eq(SessionPO::getAppName, appName)
                    .eq(SessionPO::getUserId, userId)
                    .orderByDesc(SessionPO::getUpdatedAt));

            List<Session> sessions = entities.stream()
                    .map(entity -> {
                        Session session = buildSession(entity, Optional.empty());
                        // listSessions 只列会话，不携带完整 events，避免一次命令拉出大量轨迹。
                        session.events().clear();
                        return session;
                    })
                    .toList();

            return ListSessionsResponse.builder()
                    .sessions(sessions)
                    .build();
        });
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        return Completable.fromAction(() -> {
            sessionDao.delete(new LambdaQueryWrapper<SessionPO>()
                    .eq(SessionPO::getAppName, appName)
                    .eq(SessionPO::getUserId, userId)
                    .eq(SessionPO::getSessionId, sessionId));
            sessionEventDao.delete(new LambdaQueryWrapper<SessionEventPO>()
                    .eq(SessionEventPO::getAppName, appName)
                    .eq(SessionEventPO::getUserId, userId)
                    .eq(SessionEventPO::getSessionId, sessionId));
        });
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return Single.fromCallable(() -> ListEventsResponse.builder()
                .events(loadEvents(appName, userId, sessionId, Optional.empty()))
                .build());
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        return Single.fromCallable(() -> {
            String appName = session.appName();
            String userId = session.userId();
            String sessionId = session.id();

            ensureEventIdentity(event);

            // partial 文本仅用于当前响应流；状态变化仍需更新快照，但 Event 本身不进入持久化历史。
            boolean stateChanged = applyStateDeltas(session, event);
            boolean partialEvent = event.partial().orElse(false);
            boolean persistCandidate = !partialEvent;
            List<String> deletableEventIds = deletableEventIds(event);
            boolean deleteCompactedEvents = persistCandidate
                    && compactionCleanupSettings.deleteCompactedEvents()
                    && !deletableEventIds.isEmpty();
            boolean omitPureMarkerPersistence = deleteCompactedEvents
                    && PureSlidingWindowEventSummarizer.isPureSlidingWindowMarker(event);
            boolean persistEvent = persistCandidate && !omitPureMarkerPersistence;
            if (persistEvent || stateChanged || deleteCompactedEvents) {
                transactionTemplate.executeWithoutResult(status -> {
                    if (persistEvent) {
                        persistEvent(appName, userId, sessionId, event);
                    }
                    // 终态事件更新 lastUpdateTime；partial 事件仅在确有持久状态变化时更新快照。
                    updateSessionSnapshot(session);
                    if (deleteCompactedEvents) {
                        // 仅删除 ADK 本轮实际交给 summarizer 的 Event，不能用时间或行号猜测 overlap 边界。
                        sessionEventDao.deleteCompactedEvents(SessionEventCompactionCleanup.builder()
                                .appName(appName)
                                .userId(userId)
                                .sessionId(sessionId)
                                .summaryEventId(event.id())
                                .eventIds(deletableEventIds)
                                .build());
                    }
                });
            }

            try {
                if (omitPureMarkerPersistence) {
                    // 源 Event 已被同一事务物理删除；当前 Session 也必须同步移除，才能不依赖空 marker 截断上下文。
                    session.events().removeIf(sessionEvent -> deletableEventIds.contains(sessionEvent.id()));
                } else if (!partialEvent) {
                    // 与 ADK BaseSessionService 保持一致，partial Event 不能污染下一轮模型上下文。
                    session.events().add(event);
                }
            } catch (UnsupportedOperationException ignored) {
                // 某些 Session 快照可能返回不可变列表；数据库已经完成持久化即可。
            }
            return event;
        });
    }

    @Override
    public Completable discardInvocationEvents(Session session, List<String> invocationIds) {
        return Completable.fromAction(() -> {
            List<String> resolvedInvocationIds = invocationIds == null ? List.of() : invocationIds.stream()
                    .filter(invocationId -> invocationId != null && !invocationId.isBlank())
                    .distinct()
                    .toList();
            if (resolvedInvocationIds.isEmpty()) {
                return;
            }

            // 远程模型调用早已结束；这里只开启覆盖补偿删除的短事务，不占用连接等待外部服务。
            transactionTemplate.executeWithoutResult(status -> sessionEventDao.deleteInvocationEvents(
                    SessionInvocationEventCleanup.builder()
                            .appName(session.appName())
                            .userId(session.userId())
                            .sessionId(session.id())
                            .invocationIds(resolvedInvocationIds)
                            .build()));

            // 数据库删除成功后再同步内存快照，失败时由 TransactionTemplate 抛错并保留原列表。
            session.events().removeIf(event -> resolvedInvocationIds.contains(event.invocationId()));
        });
    }

    @Override
    public Completable preserveInterruptedInvocation(
            Session session,
            List<String> deletableEventIds,
            List<Event> recoveryEvents) {
        return Completable.fromAction(() -> {
            List<String> resolvedEventIds = deletableEventIds == null ? List.of() : deletableEventIds.stream()
                    .filter(eventId -> eventId != null && !eventId.isBlank())
                    .distinct()
                    .toList();
            List<Event> resolvedRecoveryEvents = recoveryEvents == null ? List.of() : List.copyOf(recoveryEvents);
            resolvedRecoveryEvents.forEach(this::ensureEventIdentity);

            // 恢复 Event 与旧 Event 清理必须共同提交，避免只留下半套可恢复轨迹。
            transactionTemplate.executeWithoutResult(status -> {
                for (Event recoveryEvent : resolvedRecoveryEvents) {
                    persistEvent(session.appName(), session.userId(), session.id(), recoveryEvent);
                }
                if (!resolvedRecoveryEvents.isEmpty()) {
                    updateSessionSnapshot(session);
                }
                if (!resolvedEventIds.isEmpty()) {
                    sessionEventDao.deleteEventsByIds(SessionEventIdCleanup.builder()
                            .appName(session.appName())
                            .userId(session.userId())
                            .sessionId(session.id())
                            .eventIds(resolvedEventIds)
                            .build());
                }
            });

            // 数据库提交后再生成相同的内存视图，供同一进程的下一轮调用直接使用。
            session.events().removeIf(event -> resolvedEventIds.contains(event.id()));
            session.events().addAll(resolvedRecoveryEvents);
        });
    }

    private Session buildSession(SessionPO entity, Optional<GetSessionConfig> configOpt) {
        Map<String, Object> state = ADKJsonParseSupport.readMap(entity.getStateJson());

        List<Event> events = loadEvents(
                entity.getAppName(),
                entity.getUserId(),
                entity.getSessionId(),
                configOpt);

        return Session.builder(entity.getSessionId())
                .appName(entity.getAppName())
                .userId(entity.getUserId())
                .state(new ConcurrentHashMap<>(state))
                .events(new ArrayList<>(events))
                .lastUpdateTime(toInstant(entity.getUpdatedAt()))
                .build();
    }

    private List<Event> loadEvents(
            String appName,
            String userId,
            String sessionId,
            Optional<GetSessionConfig> configOpt) {
        SessionEventQuery query = SessionEventQuery.builder()
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .build();
        GetSessionConfig config = configOpt.orElse(null);
        List<SessionEventPO> rows;
        if (config != null && config.numRecentEvents().isPresent()) {
            // 显式 numRecentEvents 的语义是“最近 N 条”，不能额外插入 compaction 摘要改变调用方预期。
            query.setLimit(config.numRecentEvents().get());
            rows = sessionEventDao.selectRecentEvents(query);
        } else if (config != null && config.afterTimestamp().isPresent()) {
            // 时间边界同样在 SQL 层执行，避免先反序列化被丢弃的历史 JSON。
            query.setAfterTimestamp(config.afterTimestamp().get().getEpochSecond());
            rows = sessionEventDao.selectEventsAfterTimestamp(query);
        } else {
            // 默认恢复路径必须保留摘要之前的 overlap 原始 Event；规模由压缩后的精确删除控制。
            rows = sessionEventDao.selectSessionEvents(query);
        }
        return rows.stream()
                .map(row -> Event.fromJson(row.getEventJson()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
    }

    private void persistEvent(String appName, String userId, String sessionId, Event event) {
        // 该方法在事务内调用，先写 Event，再写 Session 快照，任一步骤失败都会共同回滚。
        SessionEventPO row = SessionEventPO.builder()
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .eventId(event.id())
                .invocationId(event.invocationId())
                .author(event.author())
                .finalResponse(event.finalResponse())
                .partial(event.partial().orElse(null))
                .compactionEvent(event.actions() != null && event.actions().compaction().isPresent())
                .eventTs(event.timestamp())
                .contentText(eventContentText(event))
                .eventJson(event.toJson())
                .createdAt(LocalDateTime.now())
                .build();
        // ADK 可能因重试或回调竞争重复投递同一 eventId；保留第一条不可变快照即可。
        sessionEventDao.upsertByEventIdentity(row);
    }

    /**
     * 仅信任由 TrackingLlmEventSummarizer 写入的 compaction metadata。
     *
     * <p>标准 EventCompaction 只给出时间范围，而 sliding-window overlap 与其他 Event 可能同秒产生；
     * 必须使用精确 eventId 集合，缺失 metadata 时保守地不删除。</p>
     */
    private List<String> deletableEventIds(Event event) {
        if (event.actions() == null || event.actions().compaction().isEmpty()) {
            return List.of();
        }
        return event.customMetadata().orElse(List.of()).stream()
                .filter(metadata -> TrackingLlmEventSummarizer.DELETABLE_EVENT_IDS_METADATA_KEY
                        .equals(metadata.key().orElse(null)))
                .flatMap(metadata -> metadata.stringListValue().stream())
                .flatMap(eventIds -> eventIds.values().stream())
                .flatMap(List::stream)
                .filter(eventId -> eventId != null && !eventId.isBlank())
                // 新摘要不应删除自身；即便 metadata 被错误构造，也要保留已成功写入的摘要。
                .filter(eventId -> !event.id().equals(eventId))
                .distinct()
                .toList();
    }

    private void ensureEventIdentity(Event event) {
        // 某些 ADK 后置事件（例如 compaction 相关事件）可能没有预先填充 eventId。
        // 即使 partial event 不落库，也保持内存事件具有稳定 identity，便于后续链路关联。
        if (event.id() == null || event.id().isBlank()) {
            event.setId(Event.generateEventId());
        }
        if (event.timestamp() <= 0) {
            event.setTimestamp(Instant.now().getEpochSecond());
        }
    }

    private void updateSessionSnapshot(Session session) {
        SessionPO snapshot = SessionPO.builder()
                .appName(session.appName())
                .userId(session.userId())
                .sessionId(session.id())
                .stateJson(ADKJsonParseSupport.toJson(sessionStateSnapshot(session.state())))
                .updatedAt(LocalDateTime.now())
                .build();
        sessionDao.updateStateByIdentity(snapshot);
    }

    private String eventContentText(Event event) {
        String text = event.stringifyContent();
        if (text != null && !text.isBlank()) {
            return text;
        }
        if (event.actions() == null || event.actions().compaction().isEmpty()) {
            return text;
        }
        return event.actions()
                .compaction()
                .map(compaction -> compaction.compactedContent().text())
                .orElse(text);
    }

    private boolean applyStateDeltas(Session session, Event event) {
        Map<String, Object> stateDelta = event.actions() == null ? Map.of() : event.actions().stateDelta();
        if (stateDelta == null || stateDelta.isEmpty()) {
            return false;
        }

        boolean changed = false;
        for (Map.Entry<String, Object> entry : stateDelta.entrySet()) {
            changed |= applyStateDelta(session, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    private boolean applyStateDelta(
            Session session,
            String key,
            Object value) {
        // 所有非 temp: 的 stateDelta 都只作为当前 Session 的 state 保存到 sessions.state_json。
        if (key.startsWith(State.TEMP_PREFIX)) {
            return false;
        }
        if (value == State.REMOVED) {
            session.state().remove(key);
        } else {
            session.state().put(key, value);
        }
        return true;
    }

    private SessionPO findSession(String appName, String userId, String sessionId) {
        return sessionDao.selectOne(new LambdaQueryWrapper<SessionPO>()
                .eq(SessionPO::getAppName, appName)
                .eq(SessionPO::getUserId, userId)
                .eq(SessionPO::getSessionId, sessionId));
    }

    private Map<String, Object> sessionStateSnapshot(Map<String, Object> state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        state.forEach((key, value) -> {
            if (!key.startsWith(State.TEMP_PREFIX)) {
                snapshot.put(key, value);
            }
        });
        return snapshot;
    }

    private Instant toInstant(LocalDateTime time) {
        return time == null ? Instant.now() : time.atZone(ZoneId.systemDefault()).toInstant();
    }
}
