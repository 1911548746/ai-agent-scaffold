package com.hh.api.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 工具确认决策请求；当前版本暂信任请求中的 userId，认证上下文接入后移除该字段。 */
public record ToolConfirmationDecisionRequestDTO(
        @NotBlank String agentId,
        @NotBlank String userId,
        @NotNull Boolean confirmed) {
}
