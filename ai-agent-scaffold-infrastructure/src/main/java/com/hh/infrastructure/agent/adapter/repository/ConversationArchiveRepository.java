package com.hh.infrastructure.agent.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.infrastructure.agent.dao.SessionDao;
import com.hh.infrastructure.agent.dao.SessionEventDao;
import com.hh.infrastructure.agent.dao.po.SessionEventPO;
import com.hh.infrastructure.agent.dao.po.SessionPO;
import com.hh.infrastructure.agent.dao.ConversationDao;
import com.hh.infrastructure.agent.dao.MessageDao;
import com.hh.infrastructure.agent.dao.po.ConversationPO;
import com.hh.infrastructure.agent.dao.po.MessagePO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ConversationArchiveRepository implements ConversationArchivePort {

    private final ConversationDao conversationMapper;
    private final MessageDao messageMapper;
    private final SessionDao sessionDao;
    private final SessionEventDao sessionEventDao;

    public ConversationArchiveRepository(
            ConversationDao conversationMapper,
            MessageDao messageMapper,
            SessionDao sessionDao,
            SessionEventDao sessionEventDao) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.sessionDao = sessionDao;
        this.sessionEventDao = sessionEventDao;
    }

    @Override
    public Long findOrCreateConversation(String appName, String userId, String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        ConversationPO conversation = ConversationPO.builder()
                .conversationId(UUID.randomUUID().toString())
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .title("conversation " + sessionId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        // 由 uk_conversations_session 裁决并发创建；不能在插入前做“是否存在”的预查询。
        conversationMapper.upsertByIdentity(conversation);

        // 无论本次插入还是并发请求已插入，均读取数据库最终记录获取稳定主键。
        ConversationPO persisted = findConversation(appName, userId, sessionId);
        if (persisted == null) {
            // Upsert 成功后都应能读到记录；读不到说明底层写入未满足持久化契约。
            throw new IllegalStateException("conversation idempotent write completed without persisted record");
        }
        return persisted.getId();
    }


    @Override
    public List<ConversationVO> listConversations(String userId) {
        List<ConversationPO> pos = conversationMapper.selectList(new LambdaQueryWrapper<ConversationPO>()
                .eq(ConversationPO::getUserId, userId)
                .orderByDesc(ConversationPO::getUpdatedAt));
        return pos.stream().map(this::toConversationVO).collect(Collectors.toList());
    }

    @Override
    public ConversationVO getConversationByBusinessId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        ConversationPO po = conversationMapper.selectOne(new LambdaQueryWrapper<ConversationPO>()
                .eq(ConversationPO::getConversationId, conversationId));
        return po == null ? null : toConversationVO(po);
    }

    @Override
    public List<MessageVO> listMessages(Long conversationId) {
        List<MessagePO> pos = messageMapper.selectList(new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getConversationId, conversationId)
                .orderByAsc(MessagePO::getCreatedAt));
        return pos.stream().map(this::toMessageVO).collect(Collectors.toList());
    }

    /**
     * 归档与 ADK Session 分别落在四张表中，必须通过同一 Spring 事务共同提交或回滚。
     *
     * <p>该方法由 application 用例从 Spring 容器外部调用，会经过事务代理；不要在本类中通过
     * {@code this} 调用它，否则 {@link Transactional} 不会生效。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversationSession(Long conversationId, String appName, String userId, String sessionId) {
        // 消息先于 Conversation 删除；即使历史库没有外键，也为后续补充外键保留正确顺序。
        LambdaQueryWrapper<MessagePO> messageQuery = new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getAppName, appName)
                .eq(MessagePO::getUserId, userId)
                .eq(MessagePO::getSessionId, sessionId);
        if (conversationId != null) {
            messageQuery.eq(MessagePO::getConversationId, conversationId);
        }
        messageMapper.delete(messageQuery);

        LambdaQueryWrapper<ConversationPO> conversationQuery = new LambdaQueryWrapper<ConversationPO>()
                .eq(ConversationPO::getAppName, appName)
                .eq(ConversationPO::getUserId, userId)
                .eq(ConversationPO::getSessionId, sessionId);
        if (conversationId != null) {
            conversationQuery.eq(ConversationPO::getId, conversationId);
        }
        conversationMapper.delete(conversationQuery);

        // SessionEvent 必须先删除，避免未来补充外键后删除 Session 时产生约束失败。
        sessionEventDao.delete(new LambdaQueryWrapper<SessionEventPO>()
                .eq(SessionEventPO::getAppName, appName)
                .eq(SessionEventPO::getUserId, userId)
                .eq(SessionEventPO::getSessionId, sessionId));
        sessionDao.delete(new LambdaQueryWrapper<SessionPO>()
                .eq(SessionPO::getAppName, appName)
                .eq(SessionPO::getUserId, userId)
                .eq(SessionPO::getSessionId, sessionId));
    }

    private ConversationVO toConversationVO(ConversationPO po) {
        return ConversationVO.builder()
                .id(po.getId())
                .conversationId(po.getConversationId())
                .appName(po.getAppName())
                .userId(po.getUserId())
                .sessionId(po.getSessionId())
                .title(po.getTitle())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    private ConversationPO findConversation(String appName, String userId, String sessionId) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<ConversationPO>()
                .eq(ConversationPO::getAppName, appName)
                .eq(ConversationPO::getUserId, userId)
                .eq(ConversationPO::getSessionId, sessionId));
    }

    private MessageVO toMessageVO(MessagePO po) {
        return MessageVO.builder()
                .id(po.getId())
                .conversationId(po.getConversationId())
                .role(po.getRole())
                .status(po.getStatus())
                .contentText(po.getContentText())
                .contentJson(po.getContentJson())
                .createdAt(po.getCreatedAt())
                .build();
    }
}
