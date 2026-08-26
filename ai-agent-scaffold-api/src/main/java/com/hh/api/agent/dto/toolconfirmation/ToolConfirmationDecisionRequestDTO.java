package com.hh.api.agent.dto.toolconfirmation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 工具确认决策请求；userId 从已验证 JWT 主体取得。 */
public record ToolConfirmationDecisionRequestDTO(
        @NotBlank String agentId,
        @NotNull Boolean confirmed
) {}
