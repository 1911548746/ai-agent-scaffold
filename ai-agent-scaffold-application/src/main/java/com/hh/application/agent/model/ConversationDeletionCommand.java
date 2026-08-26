package com.hh.application.agent.model;

/**
 * 删除一条 Agent 会话及其归档数据所需的完整身份。
 *
 * <p>conversationId 用于定位归档记录，agentId 会解析为 appName，二者再与 userId、sessionId
 * 共同限定 ADK Session。
 * 三者必须同时参与校验，避免仅按主键删除时把另一条会话的运行时数据一并清除。</p>
 */
public record ConversationDeletionCommand(
        String conversationId,
        String agentId,
        String userId,
        String sessionId) {
}
