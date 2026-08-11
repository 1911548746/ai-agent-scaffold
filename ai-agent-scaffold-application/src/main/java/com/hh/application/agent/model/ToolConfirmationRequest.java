package com.hh.application.agent.model;

import java.util.Objects;

/**
 * 项目内的待确认工具请求。
 *
 * <p>仅保留后续恢复所需的 opaque 标识和可展示文案，不把 ADK Event 或原始工具参数暴露给
 * application/trigger 之外的调用方。</p>
 */
public record ToolConfirmationRequest(
        String confirmationId,
        String adkCallId,
        String invocationId,
        String toolName,
        String message) {

    public ToolConfirmationRequest {
        requireText(confirmationId, "confirmationId");
        requireText(adkCallId, "adkCallId");
        requireText(invocationId, "invocationId");
        requireText(toolName, "toolName");
        requireText(message, "message");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
