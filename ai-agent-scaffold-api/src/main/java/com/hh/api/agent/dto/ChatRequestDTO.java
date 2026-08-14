package com.hh.api.agent.dto;

import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 聊天请求DTO
 * @param agentId AgentID
 * @param sessionId 会话ID
 * @param message 消息内容
 */
public record ChatRequestDTO(
    @NotBlank(message = "agentId不能为空")
    @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "agentId长度不能超过128个字符")
    String agentId,

    @NotBlank(message = "sessionId不能为空")
    @Size(max = RequestPayloadLimits.MAX_SESSION_ID_LENGTH, message = "sessionId长度不能超过128个字符")
    String sessionId,

    @NotBlank(message = "message不能为空")
    @Size(max = RequestPayloadLimits.MAX_TEXT_LENGTH, message = "message长度不能超过16000个字符")
    String message
) {
    /** 兼容旧测试与灰度客户端；userId 不再参与认证或业务处理。 */
    public ChatRequestDTO(String agentId, String ignoredUserId, String sessionId, String message) {
        this(agentId, sessionId, message);
    }
}
