package com.hh.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 为模块化配置编译器提供 Bean Validation 入口。
 *
 * <p>正常情况下 Spring Boot 会自动创建该 Bean；保留本配置是为了兼容某些 IDE 运行 classpath
 * 未触发 ValidationAutoConfiguration 的场景。</p>
 */
@Configuration
public class ValidationConfig {

    /**
     * 仅在容器中没有标准 Validator 时兜底创建，避免覆盖 Spring Boot 的默认实现。
     */
    @Bean
    @ConditionalOnMissingBean(jakarta.validation.Validator.class)
    public LocalValidatorFactoryBean defaultValidator() {
        return new LocalValidatorFactoryBean();
    }
}
