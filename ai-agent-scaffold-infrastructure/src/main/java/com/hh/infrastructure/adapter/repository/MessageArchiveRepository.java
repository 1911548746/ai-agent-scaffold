package com.hh.infrastructure.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.domain.agent.adapter.repository.IMessageArchiveRepository;
import com.hh.infrastructure.dao.ConversationDao;
import com.hh.infrastructure.dao.MessageDao;
import com.hh.infrastructure.dao.po.ConversationPO;
import com.hh.infrastructure.dao.po.MessagePO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MessageArchiveRepository implements IMessageArchiveRepository {

    @Resource
    private ConversationDao conversationMapper;
    @Resource
    private MessageDao messageMapper;

    @Transactional
    @Override
    public void saveUserMessage(Long conversationId, String appName, String userId, String sessionId, String text) {
        saveMessage(conversationId, appName, userId, sessionId, "user", text);
    }

    @Transactional
    @Override
    public void saveAssistantMessage(Long conversationId, String appName, String userId, String sessionId, String text) {
        saveMessage(conversationId, appName, userId, sessionId, "assistant", text);
    }

    private void saveMessage(
            Long conversationId,
            String appName,
            String userId,
            String sessionId,
            String role,
            String text) {
        MessagePO message = MessagePO.builder()
                .conversationId(conversationId)
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .role(role)
                .contentText(text)
                .contentJson(Content.fromParts(Part.fromText(text)).toJson())
                .createdAt(LocalDateTime.now())
                .build();
        messageMapper.insert(message);

        ConversationPO update = ConversationPO.builder()
                .updatedAt(LocalDateTime.now())
                .build();
        conversationMapper.update(update, new LambdaQueryWrapper<ConversationPO>()
                .eq(ConversationPO::getId, conversationId));
    }


}
