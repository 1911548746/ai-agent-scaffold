package com.hh.api.agent.dto.toolconfirmation;

/**
 * SSE 向客户端公开的待确认工具信息。
 *
 * <p>故意不暴露 ADK call id、invocation id 或原始工具参数；恢复协议由后续受控 API 处理。</p>
 */
public record ToolConfirmationResponseDTO(
        String confirmationId,
        String toolName,
        String message) {
}
