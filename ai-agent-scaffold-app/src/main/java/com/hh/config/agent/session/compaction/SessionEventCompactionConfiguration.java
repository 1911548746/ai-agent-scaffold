package com.hh.config.agent.session.compaction;

import com.hh.infrastructure.agent.agent.runtime.compaction.cleanup.SessionEventCompactionCleanupSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 app 的 Spring 绑定配置转换为基础设施可使用的不可变清理策略。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionEventCompactionProperties.class)
public class SessionEventCompactionConfiguration {

    @Bean
    public SessionEventCompactionCleanupSettings sessionEventCompactionCleanupSettings(
            SessionEventCompactionProperties properties) {
        return new SessionEventCompactionCleanupSettings(properties.isDeleteCompactedEvents());
    }
}
