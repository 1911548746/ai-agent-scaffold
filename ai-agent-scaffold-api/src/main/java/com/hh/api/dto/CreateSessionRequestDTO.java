package com.hh.api.dto;

import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建会话响应DTO
 * @param agentId AgentID
 * @param userId 用户ID
 */
public record CreateSessionRequestDTO(
    @NotBlank(message = "agentId不能为空")
    @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "agentId长度不能超过128个字符")
    String agentId,

    @NotBlank(message = "userId不能为空")
    @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "userId长度不能超过128个字符")
    String userId
) {
}
