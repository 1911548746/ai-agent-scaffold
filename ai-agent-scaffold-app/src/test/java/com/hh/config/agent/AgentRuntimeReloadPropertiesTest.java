package com.hh.config.agent;

import com.hh.config.agent.runtime.reload.AgentRuntimeReloadProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证文件监听、重试和历史配置的默认值。 */
class AgentRuntimeReloadPropertiesTest {

    @Test
    void defaultsKeepWatcherDisabledAndUseThreeAttempts() {
        AgentRuntimeReloadProperties properties = new AgentRuntimeReloadProperties();

        assertThat(properties.getWatch().isEnabled()).isFalse();
        assertThat(properties.getWatch().getDebounce()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getRetry().getInitialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getHistorySize()).isEqualTo(10);
    }

    @Test
    void rejectsNonPositiveTimingValuesThroughValidationFlags() {
        AgentRuntimeReloadProperties properties = new AgentRuntimeReloadProperties();
        properties.getWatch().setDebounce(Duration.ZERO);
        properties.getRetry().setInitialDelay(Duration.ofSeconds(-1));

        assertThat(properties.getWatch().isDebouncePositive()).isFalse();
        assertThat(properties.getRetry().isInitialDelayPositive()).isFalse();
    }
}
