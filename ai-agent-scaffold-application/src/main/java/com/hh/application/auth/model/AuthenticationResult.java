package com.hh.application.auth.model;

/** 注册或登录成功后返回给入站适配器的认证结果。 */
public record AuthenticationResult(String accessToken, long expiresInSeconds) {
}
