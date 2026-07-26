package com.hh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Session Event 压缩后的存储清理策略。
 *
 * <p>生产默认删除已被摘要覆盖的 Event；开发环境可以覆盖为 {@code false}，保留原始事件用于调试。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.agent.session.compaction", ignoreUnknownFields = false)
public class SessionEventCompactionProperties {

    private boolean deleteCompactedEvents = true;
}
