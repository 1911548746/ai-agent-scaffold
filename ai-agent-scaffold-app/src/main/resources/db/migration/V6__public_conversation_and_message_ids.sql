-- DATA-09：为会话增加对外使用的 UUID 业务标识。
-- 自增 id 继续作为内部聚簇主键，messages 仅使用内部自增 id 排序和关联。
ALTER TABLE conversations
    ADD COLUMN conversation_id VARCHAR(36) NULL AFTER id;

UPDATE conversations
SET conversation_id = UUID()
WHERE conversation_id IS NULL;

ALTER TABLE conversations
    MODIFY COLUMN conversation_id VARCHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_conversations_conversation_id (conversation_id);
