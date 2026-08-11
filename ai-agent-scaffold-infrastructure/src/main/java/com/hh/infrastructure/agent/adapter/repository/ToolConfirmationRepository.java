package com.hh.infrastructure.agent.adapter.repository;

import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.application.agent.model.ToolConfirmationRecord;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.domain.agent.model.enums.ToolConfirmationStatus;
import com.hh.infrastructure.agent.dao.ToolConfirmationDao;
import com.hh.infrastructure.agent.dao.po.ToolConfirmationPO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * HITL 待确认请求的 MySQL 归档适配器。
 *
 * <p>通过条件更新实现确认单的单节点/多节点幂等领取，避免重复执行工具。</p>
 */
@Service
public class ToolConfirmationRepository implements ToolConfirmationPort {

    private final ToolConfirmationDao toolConfirmationDao;

    public ToolConfirmationRepository(ToolConfirmationDao toolConfirmationDao) {
        this.toolConfirmationDao = toolConfirmationDao;
    }

    @Override
    public void savePending(
            Long conversationId,
            String appName,
            String userId,
            String sessionId,
            ToolConfirmationRequest request) {
        // confirmationId 由 invocationId + ADK call id 稳定生成；重复 Event 只能刷新展示信息，
        // 不能将已领取或已完成的确认单重置回 pending。
        LocalDateTime now = LocalDateTime.now();
        toolConfirmationDao.upsertPending(ToolConfirmationPO.builder()
                .confirmationId(request.confirmationId())
                .conversationId(conversationId)
                .appName(appName)
                .userId(userId)
                .sessionId(sessionId)
                .invocationId(request.invocationId())
                .adkCallId(request.adkCallId())
                .toolName(request.toolName())
                .requestMessage(request.message())
                .status(ToolConfirmationStatus.PENDING.value())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Override
    public Optional<ToolConfirmationRecord> find(String confirmationId, String userId) {
        // 查询条件必须包含 userId，防止确认 ID 被猜中后读取其他会话的审批信息。
        return Optional.ofNullable(toolConfirmationDao.selectByIdentity(confirmationId, userId))
                .map(this::toRecord);
    }

    @Override
    public boolean claimPending(String confirmationId, String userId, String appName) {
        // 真正的并发仲裁在 SQL 的 status='pending' 条件中完成，不能只依赖 application 的预查询。
        return toolConfirmationDao.claimPending(
                confirmationId, userId, appName, ToolConfirmationStatus.PROCESSING.value()) == 1;
    }

    @Override
    public boolean finish(String confirmationId, String userId, ToolConfirmationStatus status) {
        // 仅 processing 可进入终态，避免迟到的回调覆盖已经确定的审批结果。
        return toolConfirmationDao.finish(
                confirmationId, userId,
                ToolConfirmationStatus.PROCESSING.value(), status.value()) == 1;
    }

    private ToolConfirmationRecord toRecord(ToolConfirmationPO po) {
        return new ToolConfirmationRecord(
                po.getConfirmationId(), po.getConversationId(), po.getAppName(), po.getUserId(),
                po.getSessionId(), po.getInvocationId(), po.getAdkCallId(), po.getToolName(),
                po.getRequestMessage(), ToolConfirmationStatus.fromValue(po.getStatus()),
                po.getCreatedAt(), po.getUpdatedAt());
    }
}
