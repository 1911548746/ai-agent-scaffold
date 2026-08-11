package com.hh.api.agent.dto;

import java.util.List;

/**
 * 聊天响应DTO
 * @param content             响应内容
 * @param pendingConfirmations 需要后续决策的工具确认摘要
 */
public record ChatResponseDTO(
    String content,
    List<ToolConfirmationResponseDTO> pendingConfirmations
) {

    public ChatResponseDTO(String content) {
        this(content, List.of());
    }
}
