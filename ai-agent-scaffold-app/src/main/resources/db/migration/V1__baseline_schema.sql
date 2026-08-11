-- DATA-05：接管现有 sessions、session_events、conversations、messages 表。
-- 使用 CREATE IF NOT EXISTS 兼容已有开发库；后续结构增量放在更高版本脚本中。
CREATE TABLE IF NOT EXISTS sessions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    state_json JSON NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_sessions_identity (app_name, user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS session_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    invocation_id VARCHAR(128) NULL,
    author VARCHAR(128) NULL,
    final_response BOOLEAN NULL,
    partial BOOLEAN NULL,
    compaction_event BOOLEAN NULL,
    event_ts BIGINT NULL,
    content_text TEXT NULL,
    event_json JSON NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_session_events_event_id (app_name, user_id, session_id, event_id),
    KEY idx_session_events_session (app_name, user_id, session_id, id),
    KEY idx_session_events_invocation (invocation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    title VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_conversations_session (app_name, user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content_text MEDIUMTEXT NOT NULL,
    content_json JSON NULL,
    created_at DATETIME NOT NULL,
    KEY idx_messages_conversation (conversation_id, id),
    KEY idx_messages_session (app_name, user_id, session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
