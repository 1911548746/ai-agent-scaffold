-- 按 Application 选择持久化 Artifact 后端，并允许无 TTL 数据保留到 Session 手动删除。
ALTER TABLE attachment_objects
    ADD COLUMN storage_type VARCHAR(16) NOT NULL DEFAULT 'minio' AFTER id,
    MODIFY COLUMN expires_at DATETIME NULL,
    DROP INDEX uk_attachment_objects_artifact_version,
    DROP INDEX uk_attachment_objects_object_key,
    DROP INDEX idx_attachment_objects_expiry,
    ADD UNIQUE KEY uk_attachment_objects_artifact_version
        (storage_type, app_name, user_id, session_id, artifact_name, artifact_version),
    ADD UNIQUE KEY uk_attachment_objects_object_key (storage_type, object_key),
    ADD KEY idx_attachment_objects_expiry (storage_type, app_name, expires_at, id);
