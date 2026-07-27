package com.hh.api.agent.dto;

import java.util.List;

/**
 * 会话详情DTO（含消息记录）
 *
 * @param conversationId 会话ID
 * @param appName        应用名称
 * @param sessionId      会话标识
 * @param title          会话标题
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 * @param messages       消息列表
 */
public record ConversationDetailResponseDTO(
        Long conversationId,
        String appName,
        String sessionId,
        String title,
        String createdAt,
        String updatedAt,
        List<MessageItemDTO> messages
) {
}
