package com.hh.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.infrastructure.dao.ConversationDao;
import com.hh.infrastructure.dao.MessageDao;
import com.hh.infrastructure.dao.po.ConversationPO;
import com.hh.infrastructure.dao.po.MessagePO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationArchiveRepository implements ConversationArchivePort {

    private final ConversationDao conversationMapper;
    private final MessageDao messageMapper;

    public ConversationArchiveRepository(
            ConversationDao conversationMapper,
            MessageDao messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    public Long findOrCreateConversation(String appName, String userId, String sessionId) {
        LocalDateTime now = LocalDateTime.now();
        ConversationPO conversation = ConversationPO.builder()
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
    public ConversationVO getConversation(Long conversationId) {
        ConversationPO po = conversationMapper.selectById(conversationId);
        return po == null ? null : toConversationVO(po);
    }

    @Override
    public List<MessageVO> listMessages(Long conversationId) {
        List<MessagePO> pos = messageMapper.selectList(new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getConversationId, conversationId)
                .orderByAsc(MessagePO::getCreatedAt));
        return pos.stream().map(this::toMessageVO).collect(Collectors.toList());
    }

    @Override
    public void deleteConversation(Long conversationId) {
        messageMapper.delete(new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
    }

    private ConversationVO toConversationVO(ConversationPO po) {
        return ConversationVO.builder()
                .id(po.getId())
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
