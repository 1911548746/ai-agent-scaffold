package com.hh.api.dto;

/**
 * 删除会话请求参数
 * @param agentId Agent ID
 * @param userId 用户ID
 * @param sessionId 会话ID
 * @param conversationId 聊天ID
 */
public record ConversationDeleteRequestDTO(
        String agentId,
        String userId,
        String sessionId,
        Long conversationId
) {
}
