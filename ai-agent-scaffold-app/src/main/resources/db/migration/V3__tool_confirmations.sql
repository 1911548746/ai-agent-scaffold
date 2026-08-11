-- HITL-01：保存 ADK 确认请求，批准/拒绝状态机由后续 HITL 任务扩展。
CREATE TABLE IF NOT EXISTS tool_confirmations (
    confirmation_id VARCHAR(128) PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    adk_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    request_message TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_tool_confirmations_call (app_name, user_id, session_id, invocation_id, adk_call_id),
    KEY idx_tool_confirmations_session (app_name, user_id, session_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
