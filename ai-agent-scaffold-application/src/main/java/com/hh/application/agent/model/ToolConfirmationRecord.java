package com.hh.application.agent.model;

import com.hh.domain.agent.model.enums.ToolConfirmationStatus;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * application 使用的确认单快照。
 *
 * <p>ADK call id 只作为基础设施恢复任务的 opaque 标识保存，不直接暴露给 HTTP 客户端。</p>
 */
public record ToolConfirmationRecord(
        String confirmationId,
        Long conversationId,
        String appName,
        String userId,
        String sessionId,
        String invocationId,
        String adkCallId,
        String toolName,
        String message,
        ToolConfirmationStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public ToolConfirmationRecord {
        Objects.requireNonNull(confirmationId, "confirmationId must not be null");
        Objects.requireNonNull(appName, "appName must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(adkCallId, "adkCallId must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
