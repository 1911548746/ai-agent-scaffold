-- SEC-04：认证主体拥有独立账户表；不为历史 Agent 会话表补外键，避免阻断既有 user_id 数据迁移。
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(128) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_users_user_id (user_id),
    UNIQUE KEY uk_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
