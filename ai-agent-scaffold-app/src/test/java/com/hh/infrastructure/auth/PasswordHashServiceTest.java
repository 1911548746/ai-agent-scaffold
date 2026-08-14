package com.hh.infrastructure.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PBKDF2 密码哈希的离线回归测试，不接触数据库或外部服务。 */
class PasswordHashServiceTest {

    private final PasswordHashService service = new PasswordHashService();

    @Test
    void generatesDifferentSaltAndVerifiesPassword() {
        String first = service.hash("correct-password");
        String second = service.hash("correct-password");

        assertThat(first).isNotEqualTo(second);
        assertThat(service.matches("correct-password", first)).isTrue();
        assertThat(service.matches("wrong-password", first)).isFalse();
    }

    @Test
    void malformedHashIsRejected() {
        assertThat(service.matches("password", "not-a-password-hash")).isFalse();
        assertThat(service.matches("password", null)).isFalse();
    }
}
