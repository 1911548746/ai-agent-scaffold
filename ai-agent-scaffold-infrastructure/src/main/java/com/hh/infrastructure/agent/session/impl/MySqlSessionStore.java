package com.hh.infrastructure.agent.session.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hh.infrastructure.agent.session.AdkSessionStore;
import com.hh.infrastructure.dao.SessionDao;
import com.hh.infrastructure.dao.SessionEventDao;
import com.hh.infrastructure.dao.po.SessionEventPO;
import com.hh.infrastructure.dao.po.SessionPO;
import com.hh.infrastructure.support.ADKJsonParseSupport;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

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

    @Resource
    private SessionDao sessionDao;
    @Resource
    private SessionEventDao sessionEventDao;

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

            SessionPO existing = findSession(appName, userId, resolvedSessionId);
            if (existing != null) {
                return buildSession(existing, Optional.empty());
            }

            LocalDateTime now = LocalDateTime.now();
            SessionPO sessionPO = SessionPO.builder()
                    .appName(appName)
                    .userId(userId)
                    .sessionId(resolvedSessionId)
                    .stateJson(ADKJsonParseSupport.toJson(sessionStateSnapshot(state == null ? Map.of() : state)))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            sessionDao.insert(sessionPO);

            return buildSession(sessionPO, Optional.empty());
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

            // ADK 的 stateDelta 是运行时状态变更；这里必须先合并 state，再保存 session。
            Map<String, Object> stateDelta = event.actions() == null ? Map.of() : event.actions().stateDelta();
            if (stateDelta != null && !stateDelta.isEmpty()) {
                stateDelta.forEach((key, value) -> applyStateDelta(session, key, value));
            }

            persistEvent(appName, userId, sessionId, event);

            SessionPO entity = findSession(appName, userId, sessionId);
            if (entity != null) {
                entity.setStateJson(ADKJsonParseSupport.toJson(sessionStateSnapshot(session.state())));
                entity.setUpdatedAt(LocalDateTime.now());
                sessionDao.updateById(entity);
            }

            try {
                session.events().add(event);
            } catch (UnsupportedOperationException ignored) {
                // 某些 Session 快照可能返回不可变列表；数据库已经完成持久化即可。
            }
            return event;
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
        List<SessionEventPO> rows = sessionEventDao.selectList(new LambdaQueryWrapper<SessionEventPO>()
                .eq(SessionEventPO::getAppName, appName)
                .eq(SessionEventPO::getUserId, userId)
                .eq(SessionEventPO::getSessionId, sessionId)
                .orderByAsc(SessionEventPO::getId));

        List<Event> events = rows.stream()
                .map(row -> Event.fromJson(row.getEventJson()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        GetSessionConfig config = configOpt.orElse(null);
        if (config == null) {
            return events;
        }

        if (config.numRecentEvents().isPresent()) {
            int count = config.numRecentEvents().get();
            if (events.size() <= count) {
                return events;
            }
            return new ArrayList<>(events.subList(events.size() - count, events.size()));
        }

        if (config.afterTimestamp().isPresent()) {
            long afterEpochSecond = config.afterTimestamp().get().getEpochSecond();
            events.removeIf(event -> event.timestamp() <= afterEpochSecond);
        }
        return events;
    }

    private void persistEvent(String appName, String userId, String sessionId, Event event) {

        // 某些 ADK 后置事件（例如 compaction 相关事件）可能没有预先填充 eventId。
        // 数据库把 event_id 设计为非空唯一键，因此这里统一补齐，避免插入阶段失败。
        if (event.id() == null || event.id().isBlank()) {
            event.setId(Event.generateEventId());
        }
        if (event.timestamp() <= 0) {
            event.setTimestamp(Instant.now().getEpochSecond());
        }

        SessionEventPO existing = sessionEventDao.selectOne(new LambdaQueryWrapper<SessionEventPO>()
                .eq(SessionEventPO::getAppName, appName)
                .eq(SessionEventPO::getUserId, userId)
                .eq(SessionEventPO::getSessionId, sessionId)
                .eq(SessionEventPO::getEventId, event.id()));
        if (existing != null) {
            return;
        }

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
        sessionEventDao.insert(row);
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

    private void applyStateDelta(
            Session session,
            String key,
            Object value) {
        // 所有非 temp: 的 stateDelta 都只作为当前 Session 的 state 保存到 sessions.state_json。
        if (key.startsWith(State.TEMP_PREFIX)) {
            return;
        }
        if (value == State.REMOVED) {
            session.state().remove(key);
        } else {
            session.state().put(key, value);
        }
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
