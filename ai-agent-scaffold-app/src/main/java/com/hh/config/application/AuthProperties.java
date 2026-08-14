package com.hh.config.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** JWT 运行配置；签名密钥只允许通过环境变量或外部配置提供。 */
@Validated
@ConfigurationProperties(prefix = "ai.auth")
public record AuthProperties(
        @NotBlank(message = "JWT secret不能为空")
        @Size(min = 32, message = "JWT secret至少需要32个字符") String jwtSecret,
        @NotBlank(message = "JWT issuer不能为空") String jwtIssuer,
        @Min(value = 60, message = "JWT有效期至少为60秒") long accessTokenTtlSeconds) {
}
