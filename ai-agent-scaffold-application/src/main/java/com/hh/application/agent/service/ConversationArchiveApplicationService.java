package com.hh.application.agent.service;

import com.hh.application.agent.model.ConversationDeletionCommand;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.usecase.ConversationArchiveUseCase;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.domain.agent.model.valobj.ConversationVO;
import com.hh.domain.agent.model.valobj.MessageVO;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.util.List;

/**
 * 会话归档用例编排。
 */
public class ConversationArchiveApplicationService implements ConversationArchiveUseCase {

    private final ConversationArchivePort conversationArchivePort;
    private final AgentRuntimePort agentRuntimePort;

    public ConversationArchiveApplicationService(
            ConversationArchivePort conversationArchivePort,
            AgentRuntimePort agentRuntimePort) {
        this.conversationArchivePort = conversationArchivePort;
        this.agentRuntimePort = agentRuntimePort;
    }

    @Override
    public List<ConversationVO> listConversations(String userId) {
        return conversationArchivePort.listConversations(userId);
    }

    @Override
    public ConversationVO getConversation(Long conversationId) {
        ConversationVO conversation = conversationArchivePort.getConversation(conversationId);
        if (conversation == null) {
            // 保持旧客户端对不存在归档的稳定错误码；真实归属失败仍由接口层返回 ACCESS_DENIED。
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }
        return conversation;
    }

    @Override
    public List<MessageVO> listMessages(Long conversationId) {
        return conversationArchivePort.listMessages(conversationId);
    }

    @Override
    public void deleteConversation(ConversationDeletionCommand command) {
        String appName = agentRuntimePort.appName(command.agentId());
        ConversationVO conversation = conversationArchivePort.getConversation(command.conversationId());

        if (conversation != null && (!appName.equals(conversation.getAppName())
                || !command.userId().equals(conversation.getUserId())
                || !command.sessionId().equals(conversation.getSessionId()))) {
            // Conversation 主键与 Session 身份不一致时拒绝删除，避免错误请求跨会话清理运行时 Event。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }

        // 记录不存在时仍执行一次按身份删除：重复请求返回成功，并可顺便清理历史遗留的孤儿数据。
        conversationArchivePort.deleteConversationSession(
                command.conversationId(), appName, command.userId(), command.sessionId());
    }
}
