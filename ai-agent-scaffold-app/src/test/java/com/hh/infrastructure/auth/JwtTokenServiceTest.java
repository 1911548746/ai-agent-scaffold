package com.hh.infrastructure.auth;

import com.hh.domain.auth.model.UserAccount;
import com.hh.types.exception.AuthenticationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HS256 JWT 签发、签名校验和过期校验的离线回归测试。 */
class JwtTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UserAccount USER = new UserAccount(
            "user-1", "alice", "hash", null, null);

    @Test
    void issuesAndParsesTokenWithAuthenticatedSubject() {
        JwtTokenService service = new JwtTokenService(
                "01234567890123456789012345678901", "test-issuer", 3600, CLOCK);

        String token = service.generate(USER);

        assertThat(service.parse(token).userId()).isEqualTo("user-1");
        assertThat(service.parse(token).username()).isEqualTo("alice");
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        JwtTokenService service = new JwtTokenService(
                "01234567890123456789012345678901", "test-issuer", 3600, CLOCK);
        String token = service.generate(USER);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "x." + parts[2];

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(AuthenticationException.class);

        JwtTokenService expired = new JwtTokenService(
                "01234567890123456789012345678901", "test-issuer", 1,
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        String expiredToken = new JwtTokenService(
                "01234567890123456789012345678901", "test-issuer", 1, CLOCK).generate(USER);
        assertThatThrownBy(() -> expired.parse(expiredToken)).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void rejectsTokenWithUnexpectedIssuer() {
        JwtTokenService issuerA = new JwtTokenService(
                "01234567890123456789012345678901", "issuer-a", 3600, CLOCK);
        JwtTokenService issuerB = new JwtTokenService(
                "01234567890123456789012345678901", "issuer-b", 3600, CLOCK);

        String token = issuerA.generate(USER);

        assertThatThrownBy(() -> issuerB.parse(token)).isInstanceOf(AuthenticationException.class);
    }
}
