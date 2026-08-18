package com.hh.config.web;

import com.hh.config.application.ApplicationModeProperties;
import com.hh.config.application.CorsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 按运行模式限制跨域来源，避免无认证本地接口被任意网页调用。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final ApplicationModeProperties modeProperties;
    private final CorsProperties corsProperties;

    public CorsConfig(ApplicationModeProperties modeProperties, CorsProperties corsProperties) {
        this.modeProperties = modeProperties;
        this.corsProperties = corsProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var registration = registry.addMapping("/**")
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS")
                .allowCredentials(true)
                .maxAge(3600)
                .allowedHeaders("*");

        if (modeProperties.isLocal()) {
            // 本地前端通常运行在随机端口，因此只允许两个回环主机的任意端口。
            registration.allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]");
        } else if (!corsProperties.getAllowedOrigins().isEmpty()) {
            registration.allowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new));
        }
    }
}

