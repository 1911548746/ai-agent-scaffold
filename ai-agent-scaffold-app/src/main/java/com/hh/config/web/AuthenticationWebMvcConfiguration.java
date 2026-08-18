package com.hh.config.web;

import com.hh.config.application.ApplicationModeProperties;
import com.hh.application.auth.port.JwtTokenPort;
import com.hh.trigger.http.auth.AuthenticationInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Optional;

/** 将按运行模式生成的认证主体接入 Spring MVC 请求链。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApplicationModeProperties.class)
public class AuthenticationWebMvcConfiguration implements WebMvcConfigurer {

    private final AuthenticationInterceptor authenticationInterceptor;

    public AuthenticationWebMvcConfiguration(
            ApplicationModeProperties modeProperties,
            Optional<JwtTokenPort> jwtTokenPort) {
        this.authenticationInterceptor = new AuthenticationInterceptor(modeProperties.getMode(), jwtTokenPort);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/auth/**");
    }
}
