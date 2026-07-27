package com.hh.api.agent.dto;

/**
 * 消息项DTO
 *
 * @param messageId   消息ID
 * @param role        角色（user/assistant）
 * @param status      消息状态（pending/completed/failed/cancelled）
 * @param contentText 消息文本内容
 * @param createdAt   创建时间
 */
public record MessageItemDTO(
        Long messageId,
        String role,
        String status,
        String contentText,
        String createdAt
) {
}
