-- HITL-03：通用 HITL 仅保存确认单有效期与乐观锁版本，不引入特定业务工具的审计或执行回执表。
ALTER TABLE tool_confirmations
    ADD COLUMN expires_at DATETIME NULL AFTER updated_at,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER expires_at;

-- 已存在的 pending 确认单无法可靠判断原始有效期，升级后直接设为过期，避免旧操作重新获得执行资格。
UPDATE tool_confirmations
SET expires_at = CASE
    WHEN status = 'pending' THEN created_at
    ELSE updated_at
END
WHERE expires_at IS NULL;

ALTER TABLE tool_confirmations
    MODIFY COLUMN expires_at DATETIME NOT NULL,
    ADD KEY idx_tool_confirmations_expiry (status, expires_at);
