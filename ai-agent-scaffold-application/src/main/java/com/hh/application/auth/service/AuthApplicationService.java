package com.hh.application.auth.service;

import com.hh.application.auth.model.AuthenticationResult;
import com.hh.application.auth.port.JwtTokenPort;
import com.hh.application.auth.port.PasswordHashPort;
import com.hh.application.auth.port.UserAccountPort;
import com.hh.application.auth.usecase.AuthUseCase;
import com.hh.domain.auth.model.UserAccount;
import com.hh.types.enums.ResponseCode;
import com.hh.types.exception.AppException;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 认证用例编排。
 *
 * <p>用户名唯一性最终由数据库约束保证，不能使用先查询再插入作为并发安全机制。</p>
 */
public class AuthApplicationService implements AuthUseCase {

    private final UserAccountPort userAccountPort;
    private final PasswordHashPort passwordHashPort;
    private final JwtTokenPort jwtTokenPort;
    private final long accessTokenTtlSeconds;

    public AuthApplicationService(
            UserAccountPort userAccountPort,
            PasswordHashPort passwordHashPort,
            JwtTokenPort jwtTokenPort,
            long accessTokenTtlSeconds) {
        this.userAccountPort = userAccountPort;
        this.passwordHashPort = passwordHashPort;
        this.jwtTokenPort = jwtTokenPort;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    @Override
    public AuthenticationResult register(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        LocalDateTime now = LocalDateTime.now();
        UserAccount userAccount = new UserAccount(
                UUID.randomUUID().toString(),
                normalizedUsername,
                passwordHashPort.hash(password),
                now,
                now);

        if (!userAccountPort.create(userAccount)) {
            throw new AppException(ResponseCode.USERNAME_ALREADY_EXISTS);
        }
        return generateToken(userAccount);
    }

    @Override
    public AuthenticationResult login(String username, String password) {
        UserAccount userAccount = userAccountPort.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new AppException(ResponseCode.INVALID_CREDENTIALS));

        // 用户不存在或密码错误
        if (!passwordHashPort.matches(password, userAccount.passwordHash())) {
            throw new AppException(ResponseCode.INVALID_CREDENTIALS);
        }
        return generateToken(userAccount);
    }

    // 根据用户账号信息，生成 token
    private AuthenticationResult generateToken(UserAccount userAccount) {
        return new AuthenticationResult(jwtTokenPort.generate(userAccount), accessTokenTtlSeconds);
    }

    // 规范化用户名, 去除首尾空格
    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }
}
