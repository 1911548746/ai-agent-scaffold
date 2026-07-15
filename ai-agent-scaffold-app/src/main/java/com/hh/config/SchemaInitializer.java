package com.hh.config;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer implements InitializingBean {

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Override
    public void afterPropertiesSet() {
        // 这里只负责创建表；数据库 scaffold 由开发者提前创建，便于明确观察库和表。
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    state_json JSON NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_sessions_identity (app_name, user_id, session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        jdbcTemplate.execute("""
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

//        jdbcTemplate.execute("""
//                CREATE TABLE IF NOT EXISTS memories (
//                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
//                    app_name VARCHAR(128) NOT NULL,
//                    user_id VARCHAR(128) NOT NULL,
//                    source_session_id VARCHAR(128) NULL,
//                    source_event_id VARCHAR(128) NULL,
//                    author VARCHAR(128) NULL,
//                    content_text TEXT NOT NULL,
//                    content_json JSON NOT NULL,
//                    content_hash CHAR(64) NOT NULL,
//                    created_at DATETIME NOT NULL,
//                    UNIQUE KEY uk_memories_hash (app_name, user_id, content_hash),
//                    KEY idx_memories_user (app_name, user_id, id),
//                    FULLTEXT KEY ft_memories_content (content_text)
//                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
//                """);

//        jdbcTemplate.execute("""
//                CREATE TABLE IF NOT EXISTS conversations (
//                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
//                    app_name VARCHAR(128) NOT NULL,
//                    user_id VARCHAR(128) NOT NULL,
//                    session_id VARCHAR(128) NOT NULL,
//                    title VARCHAR(255) NULL,
//                    created_at DATETIME NOT NULL,
//                    updated_at DATETIME NOT NULL,
//                    UNIQUE KEY uk_conversations_session (app_name, user_id, session_id)
//                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
//                """);

//        jdbcTemplate.execute("""
//                CREATE TABLE IF NOT EXISTS messages (
//                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
//                    conversation_id BIGINT NOT NULL,
//                    app_name VARCHAR(128) NOT NULL,
//                    user_id VARCHAR(128) NOT NULL,
//                    session_id VARCHAR(128) NOT NULL,
//                    role VARCHAR(32) NOT NULL,
//                    content_text MEDIUMTEXT NOT NULL,
//                    content_json JSON NULL,
//                    created_at DATETIME NOT NULL,
//                    KEY idx_messages_conversation (conversation_id, id),
//                    KEY idx_messages_session (app_name, user_id, session_id, id)
//                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
//                """);
    }
}
