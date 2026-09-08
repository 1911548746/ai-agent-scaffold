package com.hh.config.agent.runtime.reload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 文件监听、自动重试和发布历史的部署配置。 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.agent.runtime.reload", ignoreUnknownFields = false)
public class AgentRuntimeReloadProperties {

    @Valid
    @NotNull
    private Watch watch = new Watch();

    @Valid
    @NotNull
    private Retry retry = new Retry();

    /** 只在当前 JVM 内保留的发布历史条数。 */
    @Min(1)
    private int historySize = 10;

    @Data
    public static class Watch {

        /** 默认关闭，避免生产环境意外编辑文件后立即创建新 Runtime。 */
        private boolean enabled;

        @NotNull
        private Duration debounce = Duration.ofSeconds(10);

        @AssertTrue(message = "watch.debounce must be positive")
        public boolean isDebouncePositive() {
            return debounce != null && !debounce.isZero() && !debounce.isNegative();
        }
    }

    @Data
    public static class Retry {

        /** 首次尝试计入总次数；默认总共三次。 */
        @Min(1)
        private int maxAttempts = 3;

        @NotNull
        private Duration initialDelay = Duration.ofSeconds(1);

        @AssertTrue(message = "retry.initial-delay must be positive")
        public boolean isInitialDelayPositive() {
            return initialDelay != null && !initialDelay.isZero() && !initialDelay.isNegative();
        }
    }
}
