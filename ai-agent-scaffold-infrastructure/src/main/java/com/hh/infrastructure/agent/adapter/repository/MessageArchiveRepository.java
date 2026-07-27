package com.hh.infrastructure.agent.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.hh.application.agent.port.MessageArchivePort;
import com.hh.domain.agent.model.enums.MessageStatus;
import com.hh.infrastructure.agent.dao.ConversationDao;
import com.hh.infrastructure.agent.dao.MessageDao;
import com.hh.infrastructure.agent.dao.po.ConversationPO;
import com.hh.infrastructure.agent.dao.po.MessagePO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class MessageArchiveRepository implements MessageArchivePort {

    @Resource
    private ConversationDao conversationMapper;
    @Resource
    private MessageDao messageMapper;

    @Transactional
    @Override
    public void saveUserMessage(Long conversationId, String appName, String userId, String sessionId, String text) {
        saveMessage(conversationId, appName, userId, sessionId, "user", text, MessageStatus.COMPLETED);
    }

    @Transactional
    @Override
    public void saveAssistantMessage(Long conversationId, String appName, String userId, String sessionId, String text) {
        saveMessage(conversationId, appName, userId, sessionId, "assistant", text, MessageStatus.COMPLETED);
    }

    @Transactional
    @Override
    public Long createPendingAssistantMessage(Long conversationId, String appName, String userId, String sessionId) {
        // messages.content_text 仍为非空列，pending 状态以空内容占位，避免写入未经确认的模型输出。
        return saveMessage(conversationId, appName, userId, sessionId, "assistant", "", MessageStatus.PENDING);
    }

    @Transactional
    @Override
    public void completeAssistantMessage(Long conversationId, Long messageId, String text) {
        MessagePO update = MessagePO.builder()
                .contentText(text)
                .contentJson(Content.fromParts(Part.fromText(text)).toJson())
                .status(MessageStatus.COMPLETED.value())
                .build();
        transitionPendingAssistantMessage(messageId, update);
        touchConversation(conversationId);
    }

    @Transactional
    @Override
    public void updateAssistantMessageStatus(Long conversationId, Long messageId, MessageStatus status) {
        if (status == MessageStatus.PENDING || status == MessageStatus.COMPLETED) {
            throw new IllegalArgumentException("流式 assistant 消息只能从 pending 转为 failed 或 cancelled");
        }

        MessagePO update = MessagePO.builder()
                .status(status.value())
                .build();
        transitionPendingAssistantMessage(messageId, update);
        touchConversation(conversationId);
    }

    private Long saveMessage(
            Long conversationId,
            String appName,
            String userId,
            String sessionId,
            String role,
            String text,
            MessageStatus status) {
        MessagePO message = MessagePO.builder()
                .conversationId(conversationId)
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .role(role)
                .status(status.value())
                .contentText(text)
                .contentJson(text.isEmpty() ? null : Content.fromParts(Part.fromText(text)).toJson())
                .createdAt(LocalDateTime.now())
                .build();
        messageMapper.insert(message);

        touchConversation(conversationId);
        return message.getId();
    }

    /**
     * 终态更新附带 pending 条件，防止并发的完成、失败、取消回调互相覆盖。
     */
    private void transitionPendingAssistantMessage(Long messageId, MessagePO update) {
        messageMapper.update(update, new LambdaQueryWrapper<MessagePO>()
                .eq(MessagePO::getId, messageId)
                .eq(MessagePO::getRole, "assistant")
                .eq(MessagePO::getStatus, MessageStatus.PENDING.value()));
    }

    private void touchConversation(Long conversationId) {
        ConversationPO update = ConversationPO.builder()
                .updatedAt(LocalDateTime.now())
                .build();
        conversationMapper.update(update, new LambdaQueryWrapper<ConversationPO>()
                .eq(ConversationPO::getId, conversationId));
    }


}
