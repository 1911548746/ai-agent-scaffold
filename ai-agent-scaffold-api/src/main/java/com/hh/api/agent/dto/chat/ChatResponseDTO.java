package com.hh.api.agent.dto.chat;

import com.hh.api.agent.dto.toolconfirmation.ToolConfirmationResponseDTO;

import java.util.List;

/**
 * 聊天响应DTO
 * @param content             响应内容
 * @param pendingConfirmations 需要后续决策的工具确认摘要
 */
public record ChatResponseDTO(
    String content,
    List<ToolConfirmationResponseDTO> pendingConfirmations
) {}
