package com.hh.api.dto;

/**
 * @param agentId AgentID
 * @param agentName Agent名称
 * @param agentDesc Agent描述
 */
public record AiAgentConfigResponseDTO (
    String agentId,
    String agentName,
    String agentDesc
) {
}
