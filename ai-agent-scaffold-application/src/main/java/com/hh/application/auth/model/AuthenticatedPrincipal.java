package com.hh.application.auth.model;

/** 当前请求已验证的用户主体，不携带密码、JWT 原文或 HTTP 类型。 */
public record AuthenticatedPrincipal(String userId, String username) {
}
