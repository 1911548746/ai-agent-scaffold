package com.hh.domain.agent.service.chat;

import com.hh.domain.agent.adapter.repository.IConversationArchiveRepository;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.domain.agent.service.IConversationArchiveService;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ConversationArchiveService implements IConversationArchiveService {

    @Resource
    private IConversationArchiveRepository conversationArchiveRepository;

    @Override
    public List<ConversationVO> listConversations(String userId) {
        log.info("查询会话列表 userId:{}", userId);
        return conversationArchiveRepository.listConversations(userId);
    }

    @Override
    public ConversationVO getConversation(Long conversationId) {
        log.info("查询会话详情 conversationId:{}", conversationId);
        ConversationVO conversation = conversationArchiveRepository.getConversation(conversationId);
        if (conversation == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }
        return conversation;
    }

    @Override
    public List<MessageVO> listMessages(Long conversationId) {
        log.info("查询消息列表 conversationId:{}", conversationId);
        return conversationArchiveRepository.listMessages(conversationId);
    }

    @Override
    public void deleteConversation(Long conversationId) {
        log.info("删除会话 conversationId:{}", conversationId);
        conversationArchiveRepository.deleteConversation(conversationId);
    }

}
