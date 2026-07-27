package com.hh.config.persistence;

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
                    KEY idx_session_events_compaction (compaction_event, app_name, user_id, session_id, id),
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

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    title VARCHAR(255) NULL,
                    created_at DATETIME NOT NULL,
                    updated_at DATETIME NOT NULL,
                    UNIQUE KEY uk_conversations_session (app_name, user_id, session_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    conversation_id BIGINT NOT NULL,
                    app_name VARCHAR(128) NOT NULL,
                    user_id VARCHAR(128) NOT NULL,
                    session_id VARCHAR(128) NOT NULL,
                    role VARCHAR(32) NOT NULL,
                    status VARCHAR(16) NOT NULL DEFAULT 'completed',
                    content_text MEDIUMTEXT NOT NULL,
                    content_json JSON NULL,
                    created_at DATETIME NOT NULL,
                    KEY idx_messages_conversation (conversation_id, id),
                    KEY idx_messages_session (app_name, user_id, session_id, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        ensureMessageStatusColumn();
        ensureSessionEventCompactionIndex();
    }

    /**
     * 兼容已经由旧版本初始化的数据库；历史消息没有执行状态时按已完成处理。
     *
     * <p>该兼容 DDL 仅用于本次扩展，后续 {@code DATA-05} 迁移至 Flyway 后应移除。</p>
     */
    private void ensureMessageStatusColumn() {
        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'messages'
                  AND column_name = 'status'
                """, Integer.class);
        if (columnCount != null && columnCount == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE messages
                    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'completed' AFTER role
                    """);
        }
    }

    /**
     * 为已由旧版本创建的 session_events 补齐 compaction 回放索引。
     *
     * <p>当前项目仍由 SchemaInitializer 兼容历史库；该启动期 DDL 将在 DATA-05 切换至 Flyway 后
     * 迁移为版本化脚本并从此处删除。</p>
     */
    private void ensureSessionEventCompactionIndex() {
        Integer indexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'session_events'
                  AND index_name = 'idx_session_events_compaction'
                """, Integer.class);
        if (indexCount != null && indexCount == 0) {
            jdbcTemplate.execute("""
                    ALTER TABLE session_events
                    ADD INDEX idx_session_events_compaction
                        (compaction_event, app_name, user_id, session_id, id)
                    """);
        }
    }
}
