package com.hh.api.agent.dto;

/**
 * 会话列表项DTO
 *
 * @param conversationId 会话ID
 * @param appName        应用名称
 * @param sessionId      会话标识
 * @param title          会话标题
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record ConversationListResponseDTO(
        String conversationId,
        String appName,
        String sessionId,
        String title,
        String createdAt,
        String updatedAt
) {
}
