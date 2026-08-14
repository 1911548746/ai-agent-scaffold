package com.hh.api.agent.dto;

import jakarta.validation.constraints.NotNull;

/** 工具确认决策请求；userId 从已验证 JWT 主体取得。 */
public record ToolConfirmationDecisionRequestDTO(
        @jakarta.validation.constraints.NotBlank String agentId,
        @NotNull Boolean confirmed) {

    /** 兼容旧测试和灰度客户端；userId 参数被有意忽略。 */
    public ToolConfirmationDecisionRequestDTO(String agentId, String ignoredUserId, Boolean confirmed) {
        this(agentId, confirmed);
    }
}
