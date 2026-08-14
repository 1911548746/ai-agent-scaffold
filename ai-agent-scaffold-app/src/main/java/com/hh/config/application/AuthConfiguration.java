package com.hh.config.application;

import com.hh.application.auth.port.JwtTokenPort;
import com.hh.application.auth.port.PasswordHashPort;
import com.hh.application.auth.port.UserAccountPort;
import com.hh.application.auth.service.AuthApplicationService;
import com.hh.application.auth.usecase.AuthUseCase;
import com.hh.infrastructure.auth.JwtTokenService;
import com.hh.infrastructure.auth.PasswordHashService;
import com.hh.trigger.http.auth.AuthenticationInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 认证模块的组合根，application 不直接依赖密码或 JWT 具体库。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    @Bean
    public PasswordHashPort passwordHashPort() {
        return new PasswordHashService();
    }

    @Bean
    public JwtTokenPort jwtTokenPort(AuthProperties properties) {
        return new JwtTokenService(
                properties.jwtSecret(), properties.jwtIssuer(), properties.accessTokenTtlSeconds());
    }

    /** 认证拦截器由 MVC 配置注册，只负责 HTTP 入站 JWT 校验。 */
    @Bean
    public AuthenticationInterceptor authenticationInterceptor(JwtTokenPort jwtTokenPort) {
        return new AuthenticationInterceptor(jwtTokenPort);
    }

    @Bean
    public AuthUseCase authUseCase(
            UserAccountPort userAccountPort,
            PasswordHashPort passwordHashPort,
            JwtTokenPort jwtTokenPort,
            AuthProperties properties) {
        return new AuthApplicationService(
                userAccountPort, passwordHashPort, jwtTokenPort, properties.accessTokenTtlSeconds());
    }
}
