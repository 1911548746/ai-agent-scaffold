package com.hh.api.agent.dto;

/** 确认单查询结果，不暴露 ADK call id、invocation id 或原始工具参数。 */
public record ToolConfirmationDetailResponseDTO(
        String confirmationId,
        String appName,
        String sessionId,
        String toolName,
        String message,
        String status,
        String createdAt,
        String updatedAt) {
}
