-- DATA-07：附件二进制外置到对象存储，数据库仅维护完整 Session 归属、受控元数据和 TTL。
CREATE TABLE attachment_objects (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    artifact_name VARCHAR(255) NOT NULL,
    artifact_version INT NOT NULL DEFAULT 0,
    app_name VARCHAR(128) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    UNIQUE KEY uk_attachment_objects_artifact_version
        (app_name, user_id, session_id, artifact_name, artifact_version),
    UNIQUE KEY uk_attachment_objects_object_key (object_key),
    KEY idx_attachment_objects_expiry (expires_at),
    KEY idx_attachment_objects_session (app_name, user_id, session_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
