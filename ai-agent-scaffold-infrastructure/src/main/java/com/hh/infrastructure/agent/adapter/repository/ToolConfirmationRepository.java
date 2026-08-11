package com.hh.infrastructure.agent.adapter.repository;

import com.hh.application.agent.model.ToolConfirmationRequest;
import com.hh.application.agent.port.ToolConfirmationPort;
import com.hh.domain.agent.model.enums.ToolConfirmationStatus;
import com.hh.infrastructure.agent.dao.ToolConfirmationDao;
import com.hh.infrastructure.agent.dao.po.ToolConfirmationPO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * HITL 待确认请求的 MySQL 归档适配器。
 *
 * <p>本轮只负责幂等保存 pending 请求；批准/拒绝状态机留给 HITL-02/HITL-03。</p>
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
}
