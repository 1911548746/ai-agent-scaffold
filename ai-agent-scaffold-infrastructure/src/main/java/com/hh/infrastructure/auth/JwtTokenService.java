package com.hh.infrastructure.auth;

import com.hh.application.auth.model.AuthenticatedPrincipal;
import com.hh.application.auth.port.JwtTokenPort;
import com.hh.domain.auth.model.UserAccount;
import com.hh.types.exception.AuthenticationException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Objects;

/**
 * 基于 JJWT 的 HS256 访问令牌适配器。
 *
 * <p>JJWT 负责 JWT 的序列化、签名、签名验证和标准声明校验；本类只负责把领域用户和
 * application 认证主体映射为项目自己的端口模型。</p>
 */
public class JwtTokenService implements JwtTokenPort {

    private static final String USERNAME_CLAIM = "username";

    private final SecretKey signingKey;
    private final String issuer;
    private final long accessTokenTtlSeconds;
    private final Clock clock;

    public JwtTokenService(String secret, String issuer, long accessTokenTtlSeconds) {
        this(secret, issuer, accessTokenTtlSeconds, Clock.systemUTC());
    }

    JwtTokenService(String secret, String issuer, long accessTokenTtlSeconds, Clock clock) {
        byte[] secretBytes = Objects.requireNonNull(secret, "JWT secret must not be null")
                .getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("JWT issuer must not be blank");
        }
        if (accessTokenTtlSeconds <= 0) {
            throw new IllegalArgumentException("JWT access token TTL must be positive");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = issuer;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.clock = Objects.requireNonNull(clock, "JWT clock must not be null");
    }

    @Override
    public String issue(UserAccount userAccount) {
        Date issuedAt = Date.from(clock.instant());
        Date expiration = Date.from(clock.instant().plusSeconds(accessTokenTtlSeconds));

        return Jwts.builder()
                .issuer(issuer)
                .subject(userAccount.userId())
                .claim(USERNAME_CLAIM, userAccount.username())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public AuthenticatedPrincipal parse(String token) {
        try {
            Jws<Claims> parsed = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token);

            Claims claims = parsed.getPayload();
            String userId = claims.getSubject();
            String username = claims.get(USERNAME_CLAIM, String.class);
            if (userId == null || userId.isBlank() || username == null || username.isBlank()) {
                throw new AuthenticationException();
            }
            return new AuthenticatedPrincipal(userId, username);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            // JJWT 的解析细节不对外暴露，统一映射为稳定认证失败码。
            throw new AuthenticationException();
        }
    }
}
