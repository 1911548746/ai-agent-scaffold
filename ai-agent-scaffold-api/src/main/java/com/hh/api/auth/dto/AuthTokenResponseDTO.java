package com.hh.api.auth.dto;

/** 仅返回短期访问令牌；当前版本不提供刷新令牌。 */
public record AuthTokenResponseDTO(
        String accessToken,
        String tokenType,
        long expiresInSeconds
) {
}
