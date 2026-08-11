-- 为旧库补齐消息状态和上下文压缩索引；通过 information_schema 兼容已由旧启动代码升级过的库。
SET @status_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
      AND column_name = 'status'
);
SET @add_status_sql := IF(
    @status_column_exists = 0,
    'ALTER TABLE messages ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT ''completed'' AFTER role',
    'SELECT 1'
);
PREPARE add_status_statement FROM @add_status_sql;
EXECUTE add_status_statement;
DEALLOCATE PREPARE add_status_statement;

ALTER TABLE messages
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'completed';

SET @compaction_index_exists := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'session_events'
      AND index_name = 'idx_session_events_compaction'
);
SET @add_compaction_index_sql := IF(
    @compaction_index_exists = 0,
    'ALTER TABLE session_events ADD INDEX idx_session_events_compaction (compaction_event, app_name, user_id, session_id, id)',
    'SELECT 1'
);
PREPARE add_compaction_index_statement FROM @add_compaction_index_sql;
EXECUTE add_compaction_index_statement;
DEALLOCATE PREPARE add_compaction_index_statement;
