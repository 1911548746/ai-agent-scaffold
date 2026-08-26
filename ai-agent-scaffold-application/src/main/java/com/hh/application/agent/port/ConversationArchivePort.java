package com.hh.application.agent.port;

import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;

import java.util.List;

/**
 * 对话归档用例所需的持久化能力。
 *
 * <p>该端口服务于 application 编排，而非 domain 规则；具体 MySQL 实现由 infrastructure
 * 提供，application 不关心其 DAO 或事务细节。</p>
 */
public interface ConversationArchivePort {

    Long findOrCreateConversation(String appName, String userId, String sessionId);

    List<ConversationVO> listConversations(String userId);

    ConversationVO getConversationByBusinessId(String conversationId);

    List<MessageVO> listMessages(Long conversationId);

    /**
     * 原子删除 Conversation、Message 与同一 Session 身份下的 Event、Session。
     */
    void deleteConversationSession(Long conversationId, String appName, String userId, String sessionId);

}
