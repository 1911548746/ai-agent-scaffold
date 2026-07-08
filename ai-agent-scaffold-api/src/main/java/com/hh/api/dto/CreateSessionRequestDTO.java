package com.hh.api.dto;

/**
 * 创建会话响应DTO
 * @param agentId AgentID
 * @param userId 用户ID
 */
public record CreateSessionRequestDTO(
    String agentId,
    String userId
) {
}
