package com.hh.api.dto;

import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 删除会话请求参数
 * @param agentId Agent ID
 * @param userId 用户ID
 * @param sessionId 会话ID
 * @param conversationId 聊天ID
 */
public record ConversationDeleteRequestDTO(
        @NotBlank(message = "agentId不能为空")
        @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "agentId长度不能超过128个字符")
        String agentId,

        @NotBlank(message = "userId不能为空")
        @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "userId长度不能超过128个字符")
        String userId,

        @NotBlank(message = "sessionId不能为空")
        @Size(max = RequestPayloadLimits.MAX_SESSION_ID_LENGTH, message = "sessionId长度不能超过128个字符")
        String sessionId,

        @NotNull(message = "conversationId不能为空")
        @Positive(message = "conversationId必须为正数")
        Long conversationId
) {
}
