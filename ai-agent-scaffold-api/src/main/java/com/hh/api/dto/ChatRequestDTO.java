package com.hh.api.dto;

/**
 * 聊天请求DTO
 * @param agentId AgentID
 * @param userId 用户ID
 * @param sessionId 会话ID
 * @param message 消息内容
 */
public record ChatRequestDTO(
    String agentId,
    String userId,
    String sessionId,
    String message
) {
}
