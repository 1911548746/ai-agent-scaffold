package com.hh.api.agent.dto;

import com.hh.types.validation.RequestPayloadLimits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建会话请求DTO
 * @param agentId AgentID
 */
public record CreateSessionRequestDTO(
    @NotBlank(message = "agentId不能为空")
    @Size(max = RequestPayloadLimits.MAX_IDENTIFIER_LENGTH, message = "agentId长度不能超过128个字符")
    String agentId
) {
    /** 兼容旧测试与灰度客户端；userId 不再参与认证或业务处理。 */
    public CreateSessionRequestDTO(String agentId, String ignoredUserId) {
        this(agentId);
    }
}
