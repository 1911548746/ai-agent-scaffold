package com.hh.domain.auth.model;

import java.time.LocalDateTime;

/**
 * 已注册用户的最小领域模型。
 *
 * <p>密码只以派生后的哈希字符串存在于该模型中，调用方不得向上层暴露或记录该字段。</p>
 */
public record UserAccount(
        String userId,
        String username,
        String passwordHash,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
