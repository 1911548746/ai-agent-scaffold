package com.hh.config.application;

import com.hh.types.enums.ApplicationMode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 应用启动模式配置；缺省保持线上鉴权，避免漏配时意外关闭认证。 */
@Validated
@ConfigurationProperties(prefix = "ai.application")
@Data
public class ApplicationModeProperties {

    @NotNull(message = "应用运行模式不能为空")
    private ApplicationMode mode = ApplicationMode.ONLINE;

    public boolean isLocal() {
        return mode == ApplicationMode.LOCAL;
    }
}
