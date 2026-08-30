package com.hh.application.agent.service;

import com.hh.application.agent.model.ConversationDeletionCommand;
import com.hh.application.agent.port.AgentRuntimePort;
import com.hh.application.agent.port.AttachmentStoragePort;
import com.hh.application.agent.usecase.ConversationArchiveUseCase;
import com.hh.application.agent.port.ConversationArchivePort;
import com.hh.application.agent.port.SessionLease;
import com.hh.application.agent.port.SessionOperationLockPort;
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
    private final AttachmentStoragePort attachmentStoragePort;
    private final SessionOperationLockPort sessionOperationLockPort;

    public ConversationArchiveApplicationService(
            ConversationArchivePort conversationArchivePort,
            AgentRuntimePort agentRuntimePort,
            AttachmentStoragePort attachmentStoragePort,
            SessionOperationLockPort sessionOperationLockPort) {
        this.conversationArchivePort = conversationArchivePort;
        this.agentRuntimePort = agentRuntimePort;
        this.attachmentStoragePort = attachmentStoragePort;
        this.sessionOperationLockPort = sessionOperationLockPort;
    }

    @Override
    public List<ConversationVO> listConversations(String userId) {
        return conversationArchivePort.listConversations(userId);
    }

    @Override
    public ConversationVO getConversation(String conversationId, String userId) {
        return requireOwnedConversation(conversationId, userId);
    }

    @Override
    public List<MessageVO> listMessages(String conversationId, String userId) {
        ConversationVO conversation = requireOwnedConversation(conversationId, userId);
        return conversationArchivePort.listMessages(conversation.getId());
    }

    /** 统一完成 UUID 查询和用户归属校验，避免不同用例入口产生越权分支。 */
    private ConversationVO requireOwnedConversation(String conversationId, String userId) {
        ConversationVO conversation = conversationArchivePort.getConversationByBusinessId(conversationId);
        if (conversation == null) {
            throw new AppException(ResponseCode.AGENT_NOT_FOUND);
        }
        if (userId == null || !userId.equals(conversation.getUserId())) {
            throw new AppException(ResponseCode.ACCESS_DENIED);
        }
        return conversation;
    }

    @Override
    public void deleteConversation(ConversationDeletionCommand command) {
        String appName = agentRuntimePort.appName(command.agentId());
        ConversationVO conversation = conversationArchivePort.getConversationByBusinessId(command.conversationId());

        if (conversation != null && (!appName.equals(conversation.getAppName())
                || !command.userId().equals(conversation.getUserId())
                || !command.sessionId().equals(conversation.getSessionId()))) {
            // Conversation 主键与 Session 身份不一致时拒绝删除，避免错误请求跨会话清理运行时 Event。
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER);
        }

        // 记录不存在时仍按完整 Session 身份执行清理，重复请求保持幂等。
        Long physicalConversationId = conversation == null ? null : conversation.getId();
        SessionLease lease = sessionOperationLockPort.acquire(
                command.agentId(), command.userId(), command.sessionId());
        try {
            conversationArchivePort.deleteConversationSession(
                    physicalConversationId, appName, command.userId(), command.sessionId());
            // 归档事务提交后再清理外部对象；失败时保留附件元数据，由清理任务继续重试。
            attachmentStoragePort.deleteSession(appName, command.userId(), command.sessionId());
        } finally {
            lease.release();
        }
    }
}
